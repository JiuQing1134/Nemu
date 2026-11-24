#!/system/bin/sh
# Nemu auto-unbrick guard (design docs/09).
#
# This script lives outside the disableable Nemu module:
#   /data/adb/nemu/boot_guard/boot_guard.sh
# It is installed by the module's post-fs-data/service scripts on a normal boot.
#
# Modes:
#   post-fs-data : count this boot; at threshold create rescue flag + disable module
#                  (never call pm here: package service is not up yet)
#   service      : count if post-fs-data did not run; rescue, or restore all
#                  Nemu-owned state early when the module is disabled,
#                  boot_unfreeze.sh is absent, or this boot has no early_done;
#                  wait for boot_completed, restore again, arm success watchdog
#   early        : restore all Nemu-owned state once PackageManager answers;
#                  called by boot_unfreeze.sh at the early service stage
#   rescue       : force rescue now (used by module service on an existing flag)
#   success      : reset the attempt counter (called by watchdog/App)
#   status       : print a small human-readable state (root only)
#   clear        : verify recovery, then remove rescue flag/module disable state
#
# Normal/early/late restore passes serialize on a shared restore lock
# (/data/adb/nemu/restore.lock). Rescue waits only briefly for it and then
# fails open, so a stuck lock can never block boot recovery.
#
# Guard state is private (0700 directory, 0600 state files). No ABL/boot writes.
NEMU_GUARD_OWNER=nemu_boot_autostart

GUARD_DIR=${NEMU_GUARD_DIR:-/data/adb/nemu/boot_guard}
MODULE_DIR=${NEMU_MODULE_DIR:-/data/adb/modules/nemu_boot_autostart}
MODULE_UPDATE_DIR=${NEMU_MODULE_UPDATE_DIR:-/data/adb/modules_update/nemu_boot_autostart}
THRESHOLD=${NEMU_RESCUE_THRESHOLD:-3}
STABLE_SECONDS=${NEMU_RESCUE_STABLE_SECONDS:-600}
BOOT_ID_FILE="$GUARD_DIR/last_boot_id"
ATTEMPTS_FILE="$GUARD_DIR/attempts"
RESCUE_FILE="$GUARD_DIR/rescue.flag"
CLEAN_SHUTDOWN_FILE="$GUARD_DIR/clean_shutdown"
CLEAN_SHUTDOWN_PREV_FILE="$GUARD_DIR/clean_shutdown.prev"
PLANNED_SHUTDOWN_FILE="$GUARD_DIR/planned_shutdown"
PLANNED_SHUTDOWN_PREV_FILE="$GUARD_DIR/planned_shutdown.prev"
BOOT_COMPLETED_FILE="$GUARD_DIR/boot_completed"
LAST_SHUTDOWN_FILE="$GUARD_DIR/last_shutdown"
DISABLED_MARKER_DIR="$GUARD_DIR/disabled_by_nemu"
SUSPEND_MARKER_DIR="$GUARD_DIR/suspended_by_nemu"
LOG_FILE="$GUARD_DIR/guard.log"
LOCK_DIR=${NEMU_RESTORE_LOCK_DIR:-/data/adb/nemu/restore.lock}
RESTORE_LOCK_HELD=0
RESTORE_LOCK_BOOT=
CURRENT_MODE=${1:-service}

# Known Nemu targets used by the automatic rescue fallback. The broad
# dumpsys scan (unsuspend_all) is intentionally not called automatically: it
# would clear Hail/user/MDM suspensions. It remains available for an explicit
# future action only.
KNOWN_SUSPEND="
com.hihonor.ouc
com.honor.yoyoappsug
com.honor.yoyocards
com.hihonor.suggestion
com.hihonor.sceneservice
com.hihonor.hiview
com.hihonor.hiviewpush
com.hihonor.hiviewtunnel
com.hihonor.numberidentity
com.hihonor.aipluginengine
com.hihonor.assistant
com.hihonor.magicvoice
com.hihonor.voiceengine
com.hihonor.brain
com.hihonor.mageruntime
com.hihonor.systemappsupdater
com.hihonor.visionengine
com.hihonor.magictranslate
com.hihonor.magicavatar
com.hihonor.lens
com.hihonor.hiboard
com.hihonor.magichome
com.hihonor.detectrepair
com.hihonor.cipservice
com.hihonor.assetsync
com.hihonor.assetsyncservice
com.hihonor.android.hnaps
com.hihonor.msdp
com.hihonor.securityserver
com.hihonor.awareness
"

run_timeout() {
    seconds=$1
    shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$seconds" "$@"
    else
        # Android builds without toybox timeout still get a bounded wait.
        "$@" &
        command_pid=$!
        (
            sleep "$seconds"
            kill "$command_pid" 2>/dev/null
        ) >/dev/null 2>&1 &
        timer_pid=$!
        wait "$command_pid"
        command_status=$?
        kill "$timer_pid" 2>/dev/null
        wait "$timer_pid" 2>/dev/null
        return "$command_status"
    fi
}

log_line() {
    mkdir -p "$GUARD_DIR" 2>/dev/null
    chmod 700 "$GUARD_DIR" 2>/dev/null
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE"
    chmod 600 "$LOG_FILE" 2>/dev/null
    sync 2>/dev/null
}

boot_id() {
    if [ -n "${NEMU_BOOT_ID:-}" ]; then
        printf '%s\n' "$NEMU_BOOT_ID"
        return
    fi
    cat /proc/sys/kernel/random/boot_id 2>/dev/null || date '+%s'
}

trim_guard_log() {
    # guard.log is append-only across boots; keep only the newest 400 lines.
    [ -f "$LOG_FILE" ] || return 0
    lines=$(wc -l < "$LOG_FILE" 2>/dev/null | tr -d ' ')
    case "$lines" in
        ''|*[!0-9]*) return 0 ;;
    esac
    [ "$lines" -le 400 ] && return 0
    tmp_file="$LOG_FILE.$$"
    if tail -n 400 "$LOG_FILE" > "$tmp_file" 2>/dev/null; then
        mv -f "$tmp_file" "$LOG_FILE" 2>/dev/null ||
            log_line "WARN could not rotate guard.log"
    fi
    rm -f "$tmp_file" 2>/dev/null
    chmod 600 "$LOG_FILE" 2>/dev/null
}
trim_guard_log

acquire_restore_lock() {
    # Serialize the normal/early/late restore passes. $1 is the maximum wait in
    # seconds (default 60). Returns non-zero on timeout so callers can decide
    # whether to fail open; the lock records both the owner PID and boot_id so
    # dead owners and cross-boot leftovers are reclaimed by later boots.
    max_wait=${1:-60}
    [ -n "$max_wait" ] || max_wait=60
    current=$(boot_id)
    waited=0
    steals=0
    owner_boot=
    owner_pid=
    case "$LOCK_DIR" in
        */*) mkdir -p "${LOCK_DIR%/*}" 2>/dev/null ;;
    esac
    while :; do
        if mkdir "$LOCK_DIR" 2>/dev/null; then
            printf '%s\n' "$$" > "$LOCK_DIR/pid" 2>/dev/null
            printf '%s\n' "$current" > "$LOCK_DIR/boot_id" 2>/dev/null
            chmod 700 "$LOCK_DIR" 2>/dev/null
            chmod 600 "$LOCK_DIR/pid" "$LOCK_DIR/boot_id" 2>/dev/null
            RESTORE_LOCK_HELD=1
            RESTORE_LOCK_BOOT=$current
            return 0
        fi
        owner_boot=$(cat "$LOCK_DIR/boot_id" 2>/dev/null)
        owner_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null)
        if [ -z "$owner_boot" ] && [ -z "$owner_pid" ]; then
            # The owner may still be writing its identity; one grace read.
            sleep 1
            owner_boot=$(cat "$LOCK_DIR/boot_id" 2>/dev/null)
            owner_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null)
            if [ -z "$owner_boot" ] && [ -z "$owner_pid" ]; then
                # Empty lock from a crashed creator is stale.
                rm -rf "$LOCK_DIR" 2>/dev/null
                steals=$((steals + 1))
                [ "$steals" -ge 10 ] && break
                continue
            fi
        fi
        if [ -n "$owner_boot" ] && [ "$owner_boot" != "$current" ]; then
            # Left behind by a previous boot; always stale.
            rm -rf "$LOCK_DIR" 2>/dev/null
            steals=$((steals + 1))
            [ "$steals" -ge 10 ] && break
            continue
        fi
        # A pid-only lock (boot_id missing/corrupt) must still be reclaimed
        # when that process is dead; otherwise every pass waits the full
        # timeout. A live pid from an unknown boot is left alone.
        if [ -n "$owner_boot" ] || [ -n "$owner_pid" ]; then
            stale_pid=0
            case "$owner_pid" in
                ''|*[!0-9]*) stale_pid=1 ;; # missing/corrupt pid
                *)
                    kill -0 "$owner_pid" 2>/dev/null || stale_pid=1
                    ;;
            esac
            if [ "$stale_pid" = "1" ]; then
                rm -rf "$LOCK_DIR" 2>/dev/null
                steals=$((steals + 1))
                [ "$steals" -ge 10 ] && break
                continue
            fi
        fi
        [ "$waited" -ge "$max_wait" ] && break
        sleep 1
        waited=$((waited + 1))
    done
    log_line "WARN restore lock unavailable after ${waited}s (owner pid=${owner_pid:-?} boot=${owner_boot:-?})"
    return 1
}

release_restore_lock() {
    # Delete the lock only when this process still owns it.
    [ "$RESTORE_LOCK_HELD" = "1" ] || return 0
    owner_pid=$(cat "$LOCK_DIR/pid" 2>/dev/null)
    owner_boot=$(cat "$LOCK_DIR/boot_id" 2>/dev/null)
    if [ "$owner_pid" = "$$" ] && [ "$owner_boot" = "$RESTORE_LOCK_BOOT" ]; then
        rm -rf "$LOCK_DIR" 2>/dev/null
    else
        log_line "WARN restore lock no longer owned by this process; left in place"
    fi
    RESTORE_LOCK_HELD=0
    RESTORE_LOCK_BOOT=
    return 0
}

wait_for_pm() {
    # PackageManager binder readiness probe: only real package lines count.
    max=${1:-20}
    i=0
    while [ "$i" -lt "$max" ]; do
        out=$(run_timeout 3 /system/bin/cmd package list packages 2>/dev/null)
        case "$out" in
            *package:*) return 0 ;;
        esac
        i=$((i + 1))
        sleep 1
    done
    return 1
}

count_attempt() {
    current=$(boot_id)
    mkdir -p "$GUARD_DIR" 2>/dev/null
    lock_dir="$GUARD_DIR/count.lock"
    lock_tries=0
    while ! mkdir "$lock_dir" 2>/dev/null; do
        # Publish identity as one "boot_id pid" line so a reader never sees a
        # live lock with its two halves out of sync. Fall back to the older
        # separate files if an upgraded guard left a lock behind.
        owner=$(cat "$lock_dir/owner" 2>/dev/null)
        if [ -n "$owner" ]; then
            lock_boot=${owner%% *}
            lock_pid=${owner#* }
        else
            lock_boot=$(cat "$lock_dir/boot_id" 2>/dev/null)
            lock_pid=$(cat "$lock_dir/pid" 2>/dev/null)
        fi
        if [ -z "$lock_boot" ] && [ -z "$lock_pid" ]; then
            # Owner may still be writing its identity; one grace read.
            sleep 1
            owner=$(cat "$lock_dir/owner" 2>/dev/null)
            if [ -n "$owner" ]; then
                lock_boot=${owner%% *}
                lock_pid=${owner#* }
            else
                lock_boot=$(cat "$lock_dir/boot_id" 2>/dev/null)
                lock_pid=$(cat "$lock_dir/pid" 2>/dev/null)
            fi
        fi
        if [ "$lock_boot" = "$current" ]; then
            case "$lock_pid" in
                ''|*[!0-9]*) ;;
                *)
                    if kill -0 "$lock_pid" 2>/dev/null; then
                        # Another live instance already counted this boot.
                        return 0
                    fi
                    ;;
            esac
        fi
        # A lock from a previous boot, without a PID, or whose PID is gone is
        # stale and can be replaced.
        rm -rf "$lock_dir" 2>/dev/null
        lock_tries=$((lock_tries + 1))
        [ "$lock_tries" -ge 5 ] && return 0
    done
    owner_tmp="$lock_dir/.owner.$$"
    if printf '%s %s\n' "$current" "$$" > "$owner_tmp" 2>/dev/null; then
        chmod 600 "$owner_tmp" 2>/dev/null
        mv -f "$owner_tmp" "$lock_dir/owner" 2>/dev/null ||
            rm -f "$owner_tmp" 2>/dev/null
    fi
    # Compatibility for readers from the previous guard version.
    printf '%s\n' "$current" > "$lock_dir/boot_id" 2>/dev/null
    printf '%s\n' "$$" > "$lock_dir/pid" 2>/dev/null
    chmod 600 "$lock_dir/boot_id" "$lock_dir/pid" 2>/dev/null

    last=$(cat "$BOOT_ID_FILE" 2>/dev/null)
    if [ "$last" = "$current" ]; then
        rm -rf "$lock_dir" 2>/dev/null
        return 0
    fi

    attempts=$(cat "$ATTEMPTS_FILE" 2>/dev/null)
    case "$attempts" in
        ''|*[!0-9]*) attempts=0 ;;
    esac
    clean_shutdown=$(cat "$CLEAN_SHUTDOWN_FILE" 2>/dev/null)
    clean_shutdown_prev=$(cat "$CLEAN_SHUTDOWN_PREV_FILE" 2>/dev/null)
    planned_raw=$(cat "$PLANNED_SHUTDOWN_FILE" 2>/dev/null)
    planned_boot=${planned_raw%% *}
    planned_epoch=${planned_raw#* }
    [ "$planned_epoch" = "$planned_raw" ] && planned_epoch=0
    case "$planned_epoch" in
        ''|*[!0-9]*) planned_epoch=0 ;;
    esac
    planned_prev_raw=$(cat "$PLANNED_SHUTDOWN_PREV_FILE" 2>/dev/null)
    planned_prev_boot=${planned_prev_raw%% *}
    planned_prev_epoch=${planned_prev_raw#* }
    [ "$planned_prev_epoch" = "$planned_prev_raw" ] && planned_prev_epoch=0
    case "$planned_prev_epoch" in
        ''|*[!0-9]*) planned_prev_epoch=0 ;;
    esac
    completed_raw=$(cat "$BOOT_COMPLETED_FILE" 2>/dev/null)
    completed_boot=${completed_raw%% *}
    completed_epoch=${completed_raw#* }
    [ "$completed_epoch" = "$completed_raw" ] && completed_epoch=0
    case "$completed_epoch" in
        ''|*[!0-9]*) completed_epoch=0 ;;
    esac
    reset_reason=
    reset_epoch=0
    if [ -n "$last" ]; then
        if [ "$clean_shutdown" = "$last" ] ||
            [ "$clean_shutdown_prev" = "$last" ]; then
            reset_reason=clean_shutdown
        elif [ "$planned_boot" = "$last" ]; then
            reset_reason=planned_shutdown
            reset_epoch=$planned_epoch
        elif [ "$planned_prev_boot" = "$last" ]; then
            # Preserve one unconsumed marker across a boot where guard did not
            # run; without this slot a missed boot could hide the reset.
            reset_reason=planned_shutdown
            reset_epoch=$planned_prev_epoch
        elif [ "$completed_boot" = "$last" ]; then
            # The previous boot reached the system UI. A fast adb reboot can
            # outrun the shutdown daemon's powerctl poll, but that is not a
            # boot-screen hang and must not contribute to the rescue count.
            reset_reason=boot_completed
            reset_epoch=$completed_epoch
        fi
    fi
    if [ -n "$reset_reason" ]; then
        attempts=0
        log_line "previous boot ended with ${reset_reason}; reset rescue counter"
    fi
    # Preserve one boot's shutdown classification for the App's boot-freeze
    # gate. Four whitespace-separated fields:
    #   <observed-boot-id> <planned|clean|unclean> <previous-boot-id> <epoch-s>
    # The first field binds the record to this boot, so a stale file from an
    # older boot is ignored by the App.
    last_tmp="$LAST_SHUTDOWN_FILE.$$"
    if [ "$reset_reason" = "planned_shutdown" ]; then
        printf '%s planned %s %s\n' \
            "$current" "$last" "${reset_epoch:-0}" > "$last_tmp"
    elif [ "$reset_reason" = "boot_completed" ]; then
        printf '%s completed %s %s\n' \
            "$current" "$last" "${reset_epoch:-0}" > "$last_tmp"
    elif [ "$reset_reason" = "clean_shutdown" ]; then
        printf '%s clean %s 0\n' "$current" "$last" > "$last_tmp"
    else
        printf '%s unclean %s 0\n' \
            "$current" "${last:-none}" > "$last_tmp"
    fi
    chmod 600 "$last_tmp" 2>/dev/null &&
        mv -f "$last_tmp" "$LAST_SHUTDOWN_FILE" 2>/dev/null
    rm -f "$CLEAN_SHUTDOWN_FILE" "$CLEAN_SHUTDOWN_PREV_FILE" \
        "$PLANNED_SHUTDOWN_FILE" "$PLANNED_SHUTDOWN_PREV_FILE" \
        "$BOOT_COMPLETED_FILE" 2>/dev/null
    attempts=$((attempts + 1))
    printf '%s\n' "$attempts" > "$ATTEMPTS_FILE"
    printf '%s\n' "$current" > "$BOOT_ID_FILE"
    chmod 600 "$ATTEMPTS_FILE" "$BOOT_ID_FILE" 2>/dev/null
    sync 2>/dev/null
    log_line "boot attempt ${attempts}/${THRESHOLD} (boot_id=${current})"

    if [ "$attempts" -ge "$THRESHOLD" ]; then
        rescue_now "threshold reached"
    fi
    rm -rf "$lock_dir" 2>/dev/null
}

list_suspended() {
    # dumpsys format: "  Package [name] (...)" followed by "User 0: ...
    # suspended=true ...". Extract every package whose current state is suspended.
    run_timeout 10 dumpsys package 2>/dev/null | awk '
        /^  Package \[/ {
            name = $2
            gsub(/\[|\]/, "", name)
            pkg = name
        }
        /suspended=true/ {
            if (pkg != "") print pkg
        }
    ' | sort -u
}

unsuspend_all() {
    # Keep known targets even if dumpsys output is partially parsed. Add every
    # dynamically discovered suspended package for the broad rescue pass.
    suspended=$(
        {
            printf '%s\n' $KNOWN_SUSPEND
            list_suspended
        } | sort -u
    )
    if [ -n "$suspended" ]; then
        failures_file="$GUARD_DIR/unsuspend_failures.$$"
        : > "$failures_file"
        printf '%s\n' "$suspended" | while IFS= read -r pkg; do
            [ -n "$pkg" ] || continue
            out=$(run_timeout 5 pm unsuspend --user 0 "$pkg" 2>&1)
            command_status=$?
            case "$out" in
                *"Unknown target package"*|*"Unknown package"*|*"not found"*)
                    # Installed-by-default packages can be removed by the user;
                    # there is nothing to unsuspend, so this is not a failure.
                    continue
                    ;;
            esac
            verify=$(run_timeout 5 dumpsys package "$pkg" 2>&1)
            verify_status=$?
            case "$out:$verify" in
                *"new suspended state: true"*|*"suspended=true"*)
                    log_line "WARN still suspended (different suspendingPackage?): $pkg"
                    printf '%s\n' "$pkg" >> "$failures_file"
                    ;;
                *)
                    if [ "$command_status" -ne 0 ] || [ "$verify_status" -ne 0 ]; then
                        log_line "WARN unable to verify unsuspend: $pkg (pm=$command_status dumpsys=$verify_status)"
                        printf '%s\n' "$pkg" >> "$failures_file"
                    fi
                    ;;
            esac
        done
        failures=$(wc -l < "$failures_file" 2>/dev/null | tr -d ' ')
        rm -f "$failures_file" 2>/dev/null
        if [ "${failures:-0}" -gt 0 ]; then
            log_line "unsuspend pass incomplete: ${failures} package(s) remain or could not be verified"
            sync 2>/dev/null
            return 1
        fi
        log_line "unsuspend pass verified"
    fi

    sync 2>/dev/null
    return 0
}

unsuspend_rescue() {
    # Automatic rescue must not clear suspensions owned by Hail/MDM/the user.
    # Restore only Nemu's known targets and packages carrying our marker; the
    # broad dumpsys scan in unsuspend_all stays available for an explicit
    # future action, never for a threshold-triggered rescue.
    #
    # Keep a separate counter: unsuspend_owned() also uses the global
    # `failures` variable, and calling it after the KNOWN loop would otherwise
    # erase every failure counted here on shells without local variables.
    rescue_failures=0
    for pkg in $KNOWN_SUSPEND; do
        state=$(run_timeout 5 dumpsys package "$pkg" 2>&1 |
            awk '/^[[:space:]]*User 0:/ { print; exit }')
        case "$state" in
            *"suspended=true"*) ;;
            *"suspended=false"*) continue ;;
            *)
                if package_missing "$pkg"; then
                    continue
                fi
                rescue_failures=$((rescue_failures + 1))
                log_line "WARN rescue suspend state unknown; not counted as verified: $pkg"
                continue
                ;;
        esac
        out=$(run_timeout 5 pm unsuspend --user 0 "$pkg" 2>&1)
        command_status=$?
        after=$(run_timeout 5 dumpsys package "$pkg" 2>&1 |
            awk '/^[[:space:]]*User 0:/ { print; exit }')
        case "$after" in
            *"suspended=false"*)
                log_line "rescue unsuspend known target: $pkg"
                ;;
            *)
                rescue_failures=$((rescue_failures + 1))
                log_line "WARN rescue unsuspend unverified: $pkg (pm=$command_status): $out"
                ;;
        esac
    done
    unsuspend_owned || rescue_failures=$((rescue_failures + 1))
    sync 2>/dev/null
    [ "$rescue_failures" -eq 0 ]
}

enable_disabled_targets() {
    # Nemu's reversible disable-user targets. Normal/early/late restore passes
    # and rescue all replay these, but only packages Nemu itself disabled have
    # a marker here, so a user/MDM disable policy is never undone.
    mkdir -p "$DISABLED_MARKER_DIR" 2>/dev/null
    failures=0
    # The app may own any catalog DISABLE_USER target. Scan the markers rather
    # than a hard-coded list so rescue/clear never leaves an owned package
    # disabled simply because it was not in a static allowlist.
    for marker in "$DISABLED_MARKER_DIR"/*; do
        [ -f "$marker" ] || continue
        pkg=${marker##*/}
        out=$(run_timeout 5 pm enable --user 0 "$pkg" 2>&1)
        command_status=$?
        case "$out" in
            *"Unknown target package"*|*"Unknown package"*|*"not found"*)
                rm -f "$marker" 2>/dev/null
                log_line "owned package no longer installed; marker cleared: $pkg"
                continue
                ;;
        esac
        if [ "$command_status" -eq 0 ]; then
            # `dumpsys` User 0 lines are inconsistent on this ROM (some
            # packages omit the enabled= field entirely). The test plan's
            # authoritative proof is `pm list packages -d`: a package that is
            # absent from that list is enabled, regardless of the dump shape.
            disabled_list=$(run_timeout 5 pm list packages -d --user 0 2>&1)
            disabled_status=$?
            if [ "$disabled_status" -ne 0 ]; then
                failures=$((failures + 1))
                log_line "WARN disable list unreadable; ownership marker retained: $pkg"
            elif printf '%s\n' "$disabled_list" | grep -Fqx "package:$pkg"; then
                failures=$((failures + 1))
                log_line "WARN package still in pm list -d; ownership marker retained: $pkg"
            elif rm -f "$marker" 2>/dev/null; then
                log_line "restored and verified Nemu-owned disabled target: $pkg"
            else
                failures=$((failures + 1))
                log_line "WARN package restored, but ownership marker could not be cleared: $pkg"
            fi
        else
            failures=$((failures + 1))
            log_line "WARN could not restore Nemu-owned disabled target: $pkg ($out)"
        fi
    done
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}

component_dump_complete() {
    # A truncated dump that ends at the User 0 line has no component state.
    # Require at least one non-empty line after User 0; the disabledComponents
    # block (or the next section) satisfies this. A missing block on a full
    # dump is still treated as "not disabled" by component_still_disabled.
    awk '
        /^[[:space:]]*User 0:/ { seen = 1; next }
        seen && $0 !~ /^[[:space:]]*$/ { after = 1 }
        END { exit(seen && after ? 0 : 1) }
    '
}

component_still_disabled() {
    # $1 = component (pkg/Class or pkg/.Class); dumpsys package output on stdin.
    # Returns 0 only when the dump's disabledComponents block still lists this
    # exact component. AOSP prints the bare ComponentName.getClassName()
    # (e.g. com.hihonor.ncdft.NcDftService); some OEM dumps use pkg/Class or
    # pkg/.Class. Normalize all three and compare the full class name. A block
    # ends at a blank line or at a line indented no deeper than its header.
    comp_target=$1
    comp_pkg=${comp_target%%/*}
    comp_cls=${comp_target#*/}
    case "$comp_cls" in
        .*) comp_cls="$comp_pkg$comp_cls" ;;
    esac
    awk -v pkg="$comp_pkg" -v cls="$comp_cls" '
        function entry_name(line,    pos, p, c) {
            sub(/^[[:space:]]+/, "", line)
            sub(/[[:space:]]+$/, "", line)
            sub(/[;,].*$/, "", line)
            pos = index(line, "/")
            if (pos > 0) {
                p = substr(line, 1, pos - 1)
                c = substr(line, pos + 1)
                if (c ~ /^\./) c = p c
                return c
            }
            return line
        }
        BEGIN {
            target = cls
            found = 0
            inblock = 0
            in_user0 = 0
            header_indent = -1
        }
        # Only the User 0 section matters. A work profile / privacy space
        # (User 10+) has its own hidden/disabled state and must not keep a
        # User 0 marker alive or clear it early.
        /^[[:space:]]*User [0-9]+:/ {
            if ($0 ~ /^[[:space:]]*User 0:/) in_user0 = 1
            else in_user0 = 0
            inblock = 0
            next
        }
        !in_user0 { next }
        {
            raw = $0
            match(raw, /^[ ]*/)
            indent = RLENGTH
            line = raw
            sub(/^[[:space:]]+/, "", line)
            sub(/[[:space:]]+$/, "", line)
            if (!inblock) {
                low = tolower(line)
                if (low !~ /^(disabled ?components|disabledcomponents)[[:space:]]*:/) next
                inblock = 1
                header_indent = indent
                pos = index(line, ":")
                rest = substr(line, pos + 1)
                sub(/^[[:space:]]+/, "", rest)
                if (rest != "" && entry_name(rest) == target) {
                    found = 1
                    exit
                }
                next
            }
            if (line == "") {
                inblock = 0
                next
            }
            if (indent <= header_indent) {
                inblock = 0
                next
            }
            if (entry_name(line) == target) {
                found = 1
                exit
            }
        }
        END { exit(found ? 0 : 1) }
    '
}

restore_owned_components() {
    marker_dir="$GUARD_DIR/owned_components"
    [ -d "$marker_dir" ] || return 0
    failures=0
    for marker in "$marker_dir"/*; do
        [ -f "$marker" ] || continue
        payload=$(cat "$marker" 2>/dev/null)
        component=${payload%%|*}
        original_state=${payload#*|}
        # Older markers contain only the component name; treat them as default.
        [ "$original_state" = "$payload" ] && original_state=0
        case "$component" in
            */*) ;;
            *)
                rm -f "$marker" 2>/dev/null
                log_line "WARN malformed component marker cleared: $marker"
                continue
                ;;
        esac
        pkg=${component%%/*}
        if [ "$original_state" = "1" ]; then
            out=$(run_timeout 10 pm enable --user 0 "$component" 2>&1)
        else
            out=$(run_timeout 10 pm default-state --user 0 "$component" 2>&1)
        fi
        command_status=$?
        log_line "restore component $component: $out"
        if [ "$command_status" -ne 0 ]; then
            case "$out" in
                *"Unknown component"*|*"Unknown target component"*|\
                *"Unknown package"*|*"Unknown target package"*|*"not found"*)
                    rm -f "$marker" 2>/dev/null
                    log_line "owned component no longer exists; marker cleared: $component"
                    continue
                    ;;
            esac
            failures=$((failures + 1))
            log_line "WARN component restore failed; ownership marker retained: $component"
            continue
        fi
        # pm exit 0 is not proof on this ROM. Read the package dump back and
        # only drop the ownership marker when the exact component is gone from
        # the Disabled components block (or the package no longer exists).
        verify=$(run_timeout 10 dumpsys package "$pkg" 2>&1)
        verify_status=$?
        case "$verify" in
            *"Unable to find package"*|*"Unknown package"*)
                rm -f "$marker" 2>/dev/null
                log_line "owned component package no longer installed; marker cleared: $component"
                continue
                ;;
        esac
        # A timeout can leave a dump that happens to contain User 0 but is
        # truncated before disabledComponents. Never treat that as restored.
        case "$verify_status" in
            124|137|143)
                failures=$((failures + 1))
                log_line "WARN component readback timed out; ownership marker retained: $component (dumpsys=$verify_status)"
                continue
                ;;
        esac
        # Some OEM builds exit non-zero while still printing a complete dump.
        # Require the User 0 section; an empty/partial read is fail-closed.
        case "$verify" in
            *"User 0:"*) ;;
            *)
                failures=$((failures + 1))
                log_line "WARN component readback lacks User 0 state; ownership marker retained: $component (dumpsys=$verify_status)"
                continue
                ;;
        esac
        if ! printf '%s\n' "$verify" | component_dump_complete; then
            failures=$((failures + 1))
            log_line "WARN component readback looks truncated; ownership marker retained: $component (dumpsys=$verify_status)"
            continue
        fi
        if printf '%s\n' "$verify" | component_still_disabled "$component"; then
            failures=$((failures + 1))
            log_line "WARN component still listed as disabled; ownership marker retained: $component"
            continue
        fi
        if rm -f "$marker" 2>/dev/null; then
            log_line "restored component: $component"
        else
            failures=$((failures + 1))
            log_line "WARN component restored, but ownership marker could not be cleared: $component"
        fi
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}


unsuspend_owned() {
    # Normal boots unsuspend only packages Nemu itself suspended. The app
    # writes a marker before `pm suspend` and removes it after a verified
    # restore, so a Hail/MDM/user suspension is never touched.
    #
    # Android records the suspending package. This root-side pass first
    # removes the "root" suspension; if that leaves suspended=true, it retries
    # with --for-package com.android.shell so freezes applied through Shizuku
    # are also recoverable. Only those two Nemu identities are targeted.
    #
    # Fail closed: a marker is removed only on a positive `suspended=false`
    # User 0 readback (or a confirmed uninstall). Empty/timeout/partial
    # dumpsys output retains the marker for the next pass.
    marker_dir="$SUSPEND_MARKER_DIR"
    [ -d "$marker_dir" ] || return 0
    failures=0

    user0_state() {
        printf '%s
' "$1" | awk '/User 0:/ {print; exit}'
    }

    package_gone() {
        listed=$(run_timeout 5 pm list packages --user 0 "$1" 2>&1)
        listed_status=$?
        [ "$listed_status" -eq 0 ] || return 1
        ! printf '%s
' "$listed" | grep -Fqx "package:$1"
    }

    for marker in "$marker_dir"/*; do
        [ -f "$marker" ] || continue
        pkg=${marker##*/}
        verify=$(run_timeout 5 dumpsys package "$pkg" 2>&1)
        verify_status=$?
        user_state=$(user0_state "$verify")
        case "$user_state" in
            *suspended=false*)
                # Nothing left to restore; the suspension already ended.
                rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
                continue
                ;;
            *suspended=true*) ;;
            *)
                if package_gone "$pkg"; then
                    rm -f "$marker" 2>/dev/null ||
                        failures=$((failures + 1))
                    log_line "owned suspended package no longer installed; marker cleared: $pkg"
                else
                    log_line "WARN suspend state unreadable; marker retained: $pkg (dumpsys=$verify_status)"
                    failures=$((failures + 1))
                fi
                continue
                ;;
        esac
        out=$(run_timeout 5 pm unsuspend --user 0 "$pkg" 2>&1)
        pm_status=$?
        after=$(run_timeout 5 dumpsys package "$pkg" 2>&1)
        after_status=$?
        after_state=$(user0_state "$after")
        case "$after_state" in
            *suspended=false*)
                rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
                log_line "owned suspension cleared: $pkg"
                ;;
            *)
                if package_gone "$pkg"; then
                    rm -f "$marker" 2>/dev/null ||
                        failures=$((failures + 1))
                    log_line "owned suspended package no longer installed; marker cleared: $pkg"
                    continue
                fi
                # Compatibility with freezes applied through Shizuku:
                # Android records com.android.shell as the suspender, so the
                # plain root unsuspend above cannot remove it. This removes
                # only the shell-owned suspension, never Hail/MDM/user ones.
                out2=$(run_timeout 5 pm unsuspend --user 0 \
                    --for-package com.android.shell "$pkg" 2>&1)
                after2=$(run_timeout 5 dumpsys package "$pkg" 2>&1)
                after2_state=$(user0_state "$after2")
                case "$after2_state" in
                    *suspended=false*)
                        rm -f "$marker" 2>/dev/null ||
                            failures=$((failures + 1))
                        log_line "owned shell suspension cleared: $pkg"
                        ;;
                    *)
                        log_line "WARN owned unsuspend unverified; marker retained: $pkg (pm=$pm_status dumpsys=$after_status): $out"
                        failures=$((failures + 1))
                        ;;
                esac
                ;;
        esac
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}
restore_owned_hidden() {
    marker_dir="$GUARD_DIR/hidden_by_nemu"
    [ -d "$marker_dir" ] || return 0
    failures=0
    for marker in "$marker_dir"/*; do
        [ -f "$marker" ] || continue
        pkg=${marker##*/}
        out=$(run_timeout 10 pm unhide --user 0 "$pkg" 2>&1)
        command_status=$?
        case "$out" in
            *"Unknown target package"*|*"Unknown package"*|*"not found"*)
                rm -f "$marker" 2>/dev/null
                log_line "owned hidden package no longer installed; marker cleared: $pkg"
                continue
                ;;
        esac
        if [ "$command_status" -eq 0 ]; then
            # pm exit 0 is not proof on this ROM; require a real readback
            # before deleting the only rescue record.
            verify=$(run_timeout 10 dumpsys package "$pkg" 2>&1)
            user_state=$(printf '%s\n' "$verify" |
                awk '/^[[:space:]]*User 0:/ { print; exit }')
            case "$user_state" in
                *"hidden=false"*)
                    rm -f "$marker" 2>/dev/null ||
                        failures=$((failures + 1))
                    log_line "unhide owned target: $pkg"
                    ;;
                *)
                    failures=$((failures + 1))
                    log_line "WARN unhide not confirmed; marker retained: $pkg"
                    ;;
            esac
        else
            failures=$((failures + 1))
            log_line "WARN unhide failed; ownership marker retained: $pkg ($out)"
        fi
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}

appops_mode_from_output() {
    # Package-level line: "RUN_ANY_IN_BACKGROUND: allow" optionally followed
    # by "; time=...", "; rejectTime=...", "; duration=..." or " (running)".
    # UID-level lines start with "Uid mode:" and must be skipped, otherwise a
    # UID mode would be compared against the package marker.
    awk '
        {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            if (line !~ /^RUN_ANY_IN_BACKGROUND[[:space:]]*:/) next
            pos = index(line, ":")
            value = substr(line, pos + 1)
            sub(/^[[:space:]]+/, "", value)
            sub(/^mode=/, "", value)
            sub(/[[:space:];(].*$/, "", value)
            print tolower(value)
            exit
        }
    '
}

package_missing() {
    # Returns 0 only when PackageManager answered and the exact package is not
    # listed. A timeout/read failure returns 1, so a marker is never dropped on
    # an unknown PM state.
    listed=$(run_timeout 5 pm list packages --user 0 "$1" 2>&1)
    [ $? -eq 0 ] || return 1
    ! printf '%s\n' "$listed" | grep -Fqx "package:$1"
}

restore_owned_appops() {
    marker_dir="$GUARD_DIR/appops_owned"
    [ -d "$marker_dir" ] || return 0
    failures=0
    for marker in "$marker_dir"/*; do
        [ -f "$marker" ] || continue
        pkg=${marker##*/}
        mode=$(cat "$marker" 2>/dev/null)
        case "$mode" in
            allow|ignore|deny|default|foreground) ;;
            *)
                # An unusable baseline cannot be restored; keeping it would
                # make every boot incomplete and permanently block clear.
                rm -f "$marker" 2>/dev/null
                log_line "WARN malformed AppOps marker cleared: $pkg ($mode)"
                continue
                ;;
        esac
        out=$(run_timeout 5 appops set --user 0 "$pkg" \
            RUN_ANY_IN_BACKGROUND "$mode" 2>&1)
        command_status=$?
        verify=$(run_timeout 5 appops get --user 0 "$pkg" \
            RUN_ANY_IN_BACKGROUND 2>&1)
        actual=$(printf '%s\n' "$verify" | appops_mode_from_output)
        if [ "$command_status" -ne 0 ] && package_missing "$pkg"; then
            rm -f "$marker" 2>/dev/null
            log_line "owned AppOps package no longer installed; marker cleared: $pkg"
            continue
        fi
        if [ "$command_status" -eq 0 ] && [ "$actual" = "$mode" ]; then
            rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
            log_line "restore AppOps mode: $pkg -> $mode"
        else
            failures=$((failures + 1))
            log_line "WARN AppOps restore unverified; marker retained: $pkg (pm=$command_status expected=$mode actual=${actual:-none}): $out"
        fi
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}

standby_bucket_from_output() {
    # `am get-standby-bucket` prints the integer bucket, sometimes with a
    # label. Take the first integer token and compare exactly ("1" != "10").
    awk '
        {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9]+$/) { print $i; exit }
            }
        }
    '
}

restore_owned_standby() {
    marker_dir="$GUARD_DIR/standby_owned"
    [ -d "$marker_dir" ] || return 0
    failures=0
    for marker in "$marker_dir"/*; do
        [ -f "$marker" ] || continue
        pkg=${marker##*/}
        bucket=$(cat "$marker" 2>/dev/null)
        case "$bucket" in
            ''|*[!0-9]*)
                rm -f "$marker" 2>/dev/null
                log_line "WARN malformed standby marker cleared: $pkg ($bucket)"
                continue
                ;;
        esac
        out=$(run_timeout 5 am set-standby-bucket --user 0 "$pkg" "$bucket" 2>&1)
        command_status=$?
        if [ "$command_status" -ne 0 ] && package_missing "$pkg"; then
            rm -f "$marker" 2>/dev/null
            log_line "owned standby package no longer installed; marker cleared: $pkg"
            continue
        fi
        verify=$(run_timeout 5 am get-standby-bucket --user 0 "$pkg" 2>&1)
        actual=$(printf '%s\n' "$verify" | standby_bucket_from_output)
        if [ "$command_status" -eq 0 ] && [ "$actual" = "$bucket" ]; then
            rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
            log_line "restore standby bucket: $pkg -> $bucket"
        else
            failures=$((failures + 1))
            log_line "WARN standby restore unverified; marker retained: $pkg (pm=$command_status expected=$bucket actual=${actual:-none}): $out"
        fi
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
}

restore_owned_state() {
    # Normal/early boot: restore only state Nemu itself owns (markers). Every
    # step runs even when an earlier one failed; the aggregate status tells the
    # caller whether the pass was complete without aborting the rest.
    state_status=0
    enable_disabled_targets ||
        { state_status=1; log_line "WARN owned disable-user restore incomplete"; }
    restore_owned_components ||
        { state_status=1; log_line "WARN owned component restore incomplete"; }
    restore_owned_hidden ||
        { state_status=1; log_line "WARN owned hidden restore incomplete"; }
    restore_owned_appops ||
        { state_status=1; log_line "WARN owned AppOps restore incomplete"; }
    restore_owned_standby ||
        { state_status=1; log_line "WARN owned standby restore incomplete"; }
    unsuspend_owned ||
        { state_status=1; log_line "WARN owned suspend restore incomplete"; }
    if [ "$state_status" -ne 0 ]; then
        log_line "owned-state restore incomplete; unverified ownership markers retained"
    else
        log_line "owned-state restore verified"
    fi
    return "$state_status"
}

restore_all_state() {
    # Rescue: restore Nemu-owned state plus the known Nemu targets. Foreign
    # suspensions are deliberately left alone (see unsuspend_rescue).
    state_status=0
    enable_disabled_targets ||
        { state_status=1; log_line "WARN rescue disable-user restore incomplete"; }
    restore_owned_components ||
        { state_status=1; log_line "WARN rescue component restore incomplete"; }
    restore_owned_hidden ||
        { state_status=1; log_line "WARN rescue hidden restore incomplete"; }
    restore_owned_appops ||
        { state_status=1; log_line "WARN rescue AppOps restore incomplete"; }
    restore_owned_standby ||
        { state_status=1; log_line "WARN rescue standby restore incomplete"; }
    unsuspend_rescue ||
        { state_status=1; log_line "WARN rescue unsuspend incomplete"; }
    return "$state_status"
}

rescue_now() {
    reason=${1:-manual}
    log_line "RESCUE: $reason"
    printf '%s\n' "$(date '+%s' 2>/dev/null)" > "$RESCUE_FILE"
    chmod 600 "$RESCUE_FILE" 2>/dev/null

    for d in "$MODULE_DIR" "$MODULE_UPDATE_DIR"; do
        if [ -d "$d" ]; then
            touch "$d/disable" 2>/dev/null
            log_line "disabled module at $d"
        fi
    done

    # Rescue restores Nemu-owned markers plus Nemu's known target list. It
    # deliberately does not scan/clear arbitrary foreign suspensions. It waits
    # only briefly for the shared restore lock and then fails open.
    if [ "$CURRENT_MODE" != "post-fs-data" ]; then
        # Do not spend the rescue budget on a PackageManager that has not
        # answered yet; every pm/dumpsys call would burn its own timeout.
        if wait_for_pm 30; then
            acquire_restore_lock 10 ||
                log_line "WARN rescue restore lock unavailable; continuing without lock"
            if restore_all_state; then
                log_line "RESCUE recovery commands verified"
            else
                log_line "RESCUE incomplete; ownership markers retained where unverified"
            fi
            release_restore_lock
        else
            log_line "WARN RESCUE deferred: PackageManager not ready after 30s"
        fi
    else
        log_line "post-fs-data cannot call pm; service stage will unfreeze"
    fi
    log_line "RESCUE complete"
    sync 2>/dev/null
}

wait_boot_completed() {
    i=0
    while [ "$i" -lt 60 ]; do
        [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && return 0
        i=$((i + 1))
        sleep 2
    done
    return 1
}

record_boot_completed() {
    # A boot that reaches sys.boot_completed=1 is not a boot-screen hang.
    # Record it so the next boot does not count a fast adb reboot as a failed
    # start even when the shutdown daemon misses the short powerctl window.
    mkdir -p "$GUARD_DIR" 2>/dev/null
    boot_completed_tmp="$BOOT_COMPLETED_FILE.$$"
    if printf '%s %s\n' "$(boot_id)" "$(date '+%s' 2>/dev/null)" \
        > "$boot_completed_tmp" 2>/dev/null; then
        chmod 600 "$boot_completed_tmp" 2>/dev/null
        mv -f "$boot_completed_tmp" "$BOOT_COMPLETED_FILE" 2>/dev/null ||
            rm -f "$boot_completed_tmp" 2>/dev/null
    fi
    chmod 600 "$BOOT_COMPLETED_FILE" 2>/dev/null
}

arm_success_watchdog() {
    current=$(boot_id)
    # Background subshell: after the stability window, if this same boot is
    # still running and boot_completed=1, reset the counter. If the process is
    # killed by the system, the counter remains and the next boot increments.
    if command -v nohup >/dev/null 2>&1; then
        nohup sh -c "
            sleep $STABLE_SECONDS
            [ \"\$(cat '$BOOT_ID_FILE' 2>/dev/null)\" = '$current' ] || exit 0
            [ \"\$(getprop sys.boot_completed 2>/dev/null)\" = '1' ] || exit 0
            printf '0\n' > '$ATTEMPTS_FILE'
            chmod 600 '$ATTEMPTS_FILE' 2>/dev/null
            printf '%s\n' \"\$(date '+%s' 2>/dev/null)\" > '$GUARD_DIR/last_success'
            chmod 600 '$GUARD_DIR/last_success' 2>/dev/null
        " >/dev/null 2>&1 &
    else
        (
            sleep "$STABLE_SECONDS"
            [ "$(cat "$BOOT_ID_FILE" 2>/dev/null)" = "$current" ] || exit 0
            [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] || exit 0
            printf '0\n' > "$ATTEMPTS_FILE"
            chmod 600 "$ATTEMPTS_FILE" 2>/dev/null
            date '+%s' > "$GUARD_DIR/last_success" 2>/dev/null
        ) >/dev/null 2>&1 &
    fi
}

clear_refuse() {
    log_line "$1"
    release_restore_lock
    exit 1
}

# Tests source this file with NEMU_GUARD_LIB_ONLY=1 to exercise the parsers
# against captured dumpsys/appops fixtures without running a guard mode.
if [ "${NEMU_GUARD_LIB_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

case "${1:-service}" in
    post-fs-data)
        count_attempt
        # Do not call pm here: package service is not available yet.
        ;;
    service)
        mkdir -p "$GUARD_DIR" 2>/dev/null
        chmod 700 "$GUARD_DIR" 2>/dev/null
        count_attempt
        if [ -f "$RESCUE_FILE" ]; then
            log_line "rescue flag present; unfreezing all and skipping app start"
            # pm/dumpsys may not be ready at the early service stage. Wait for
            # PackageManager first, then run a bounded pass; after
            # boot_completed run a second. If boot_completed never arrives
            # (the boot-screen hang rescue exists for), make one more
            # PM-gated attempt instead of relying solely on boot_completed.
            if wait_for_pm 30; then
                acquire_restore_lock 10 ||
                    log_line "WARN rescue restore lock unavailable; continuing without lock"
                restore_all_state || log_line "WARN rescue restore pass 1 incomplete"
                release_restore_lock
            else
                log_line "WARN rescue: PackageManager not ready for pass 1"
            fi
            if wait_boot_completed; then
                acquire_restore_lock 60 ||
                    log_line "WARN rescue restore lock unavailable; continuing without lock"
                restore_all_state || log_line "WARN rescue restore pass 2 incomplete"
                release_restore_lock
            else
                log_line "WARN rescue: boot_completed timeout; retrying once if PM is ready"
                sleep 10
                if wait_for_pm 20; then
                    acquire_restore_lock 60 ||
                        log_line "WARN rescue retry lock unavailable; continuing without lock"
                    restore_all_state || log_line "WARN rescue retry incomplete"
                    release_restore_lock
                else
                    log_line "WARN rescue: PackageManager still not ready for retry"
                fi
            fi
            exit 0
        fi
        # Early safety prep. boot_unfreeze.sh, when installed, already performs
        # the PM-ready early pass in parallel; a second full pass here could eat
        # service.sh's timeout and starve the late pass and watchdog. If the
        # module is disabled (rescue) or boot_unfreeze is absent, the module
        # script will not run it, so this guard makes one bounded attempt after
        # PackageManager answers.
        early_done=$(cat "$GUARD_DIR/early_done" 2>/dev/null)
        if [ -f "$MODULE_DIR/disable" ] ||
            [ ! -f "$MODULE_DIR/boot_unfreeze.sh" ] ||
            [ "$early_done" != "$(boot_id)" ]; then
            if wait_for_pm 20; then
                if acquire_restore_lock 60; then
                    restore_owned_state ||
                        log_line "WARN early owned-state restore incomplete"
                    release_restore_lock
                else
                    log_line "WARN early restore lock unavailable; running early restore without lock"
                    restore_owned_state ||
                        log_line "WARN early owned-state restore incomplete"
                fi
            else
                log_line "WARN PackageManager not ready after 20s; skipping early owned-state restore"
            fi
        fi
        # The late pass always gets a chance to run: every wait above is bounded
        # and every pm/dumpsys call is individually timed out.
        if wait_boot_completed; then
            record_boot_completed
            if acquire_restore_lock 60; then
                restore_owned_state ||
                    log_line "WARN late owned-state restore incomplete"
                release_restore_lock
            else
                log_line "WARN late restore lock unavailable; running late restore without lock"
                restore_owned_state ||
                    log_line "WARN late owned-state restore incomplete"
            fi
        else
            log_line "boot_completed timeout; arming watchdog for a late completion"
        fi
        # Arm even after a timeout: the watchdog re-checks boot_completed after
        # the stability window, so a slow but successful boot still clears the
        # failed-boot counter instead of tripping a false rescue.
        arm_success_watchdog
        ;;
    early)
        # Called by boot_unfreeze.sh once PackageManager answers. Must not count
        # attempts or touch the rescue flag; the exit code reflects the restore.
        mkdir -p "$GUARD_DIR" 2>/dev/null
        chmod 700 "$GUARD_DIR" 2>/dev/null
        acquire_restore_lock 60 ||
            log_line "WARN early restore lock unavailable; continuing without lock"
        restore_owned_state
        restore_status=$?
        release_restore_lock
        exit "$restore_status"
        ;;
    rescue)
        mkdir -p "$GUARD_DIR" 2>/dev/null
        rescue_now "explicit"
        ;;
    success)
        printf '0\n' > "$ATTEMPTS_FILE"
        chmod 600 "$ATTEMPTS_FILE" 2>/dev/null
        date '+%s' > "$GUARD_DIR/last_success" 2>/dev/null
        ;;
    status)
        printf 'rescue=%s attempts=%s boot_id=%s\n' \
            "$( [ -f "$RESCUE_FILE" ] && echo yes || echo no )" \
            "$(cat "$ATTEMPTS_FILE" 2>/dev/null || echo 0)" \
            "$(cat "$BOOT_ID_FILE" 2>/dev/null || echo none)"
        ;;
    clear)
        # Stay in rescue if either recovery pass fails. Markers are deleted only
        # after their package is verified enabled, so another rescue can retry.
        # Serialize with early/late/rescue so a clear cannot delete a marker
        # while another pass is reading it.
        acquire_restore_lock 60 ||
            log_line "WARN clear restore lock unavailable; continuing without lock"
        if ! restore_owned_components; then
            clear_refuse "rescue clear refused: one or more Nemu-owned components could not be restored"
        fi
        if ! restore_owned_hidden; then
            clear_refuse "rescue clear refused: one or more Nemu-hidden packages could not be unhidden"
        fi
        if ! restore_owned_appops; then
            clear_refuse "rescue clear refused: one or more AppOps modes could not be restored"
        fi
        if ! restore_owned_standby; then
            clear_refuse "rescue clear refused: one or more standby buckets could not be restored"
        fi
        if ! unsuspend_owned; then
            clear_refuse "rescue clear refused: one or more Nemu-owned suspensions could not be cleared"
        fi
        log_line "clear: Nemu-owned suspensions cleared; foreign suspensions are not touched"
        if ! enable_disabled_targets; then
            clear_refuse "rescue clear refused: one or more Nemu-disabled packages remain disabled"
        fi
        for d in "$MODULE_DIR" "$MODULE_UPDATE_DIR"; do
            rm -f "$d/disable" 2>/dev/null || {
                clear_refuse "rescue clear refused: could not re-enable module at $d"
            }
        done
        rm -f "$ATTEMPTS_FILE" "$BOOT_ID_FILE" "$GUARD_DIR/last_success" \
            "$CLEAN_SHUTDOWN_FILE" "$CLEAN_SHUTDOWN_PREV_FILE" \
            "$PLANNED_SHUTDOWN_FILE" "$PLANNED_SHUTDOWN_PREV_FILE" \
            "$BOOT_COMPLETED_FILE" "$LAST_SHUTDOWN_FILE" \
            "$GUARD_DIR/early_done" 2>/dev/null || {
            clear_refuse "rescue clear refused: could not clear guard counters"
        }
        rm -f "$RESCUE_FILE" 2>/dev/null || {
            clear_refuse "rescue clear refused: could not clear rescue flag"
        }
        release_restore_lock
        log_line "rescue cleared by user"
        ;;
    *)
        echo "usage: $0 {post-fs-data|service|early|rescue|success|status|clear}" >&2
        exit 2
        ;;
esac
