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
#   service      : count if post-fs-data did not run; rescue or early-unsuspend,
#                  wait for boot_completed, unsuspend again, arm success watchdog
#   rescue       : force rescue now (used by module service on an existing flag)
#   success      : reset the attempt counter (called by watchdog/App)
#   status       : print a small human-readable state (root only)
#   clear        : verify recovery, then remove rescue flag/module disable state
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
DISABLED_MARKER_DIR="$GUARD_DIR/disabled_by_nemu"
SUSPEND_MARKER_DIR="$GUARD_DIR/suspended_by_nemu"
LOG_FILE="$GUARD_DIR/guard.log"
CURRENT_MODE=${1:-service}

# Known Nemu targets are included on every pass. Dumpsys adds any other
# suspended packages, so rescue also clears suspensions left by older builds.
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

count_attempt() {
    current=$(boot_id)
    mkdir -p "$GUARD_DIR" 2>/dev/null
    lock_dir="$GUARD_DIR/count.lock"
    if ! mkdir "$lock_dir" 2>/dev/null; then
        lock_boot=$(cat "$lock_dir/boot_id" 2>/dev/null)
        # Another instance already counted this boot; a lock from a previous
        # boot is stale and can be replaced.
        [ "$lock_boot" = "$current" ] && return 0
        rm -rf "$lock_dir" 2>/dev/null
        mkdir "$lock_dir" 2>/dev/null || return 0
    fi
    printf '%s\n' "$current" > "$lock_dir/boot_id" 2>/dev/null

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
    if [ -n "$last" ] && [ "$clean_shutdown" = "$last" ]; then
        attempts=0
        log_line "previous boot had a verified clean unfreeze; reset rescue counter"
    fi
    rm -f "$CLEAN_SHUTDOWN_FILE" 2>/dev/null
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

enable_disabled_targets() {
    # Nemu's reversible disable-user targets. Only rescue mode enables them;
    # a normal boot must not undo the user's cloud/security policy.
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
            verify=$(run_timeout 5 dumpsys package "$pkg" 2>&1)
            verify_status=$?
            user_state=$(printf '%s\n' "$verify" | awk '/User 0:/ { print; exit }')
            case "$user_state" in
                *"enabled=0"*|*"enabled=1"*)
                    if rm -f "$marker" 2>/dev/null; then
                        log_line "restored and verified Nemu-owned disabled target: $pkg"
                    else
                        failures=$((failures + 1))
                        log_line "WARN package restored, but ownership marker could not be cleared: $pkg"
                    fi
                    ;;
                *)
                    failures=$((failures + 1))
                    log_line "WARN enable could not be verified; ownership marker retained: $pkg (dumpsys=$verify_status; $user_state)"
                    ;;
            esac
        else
            failures=$((failures + 1))
            log_line "WARN could not restore Nemu-owned disabled target: $pkg ($out)"
        fi
    done
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
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
            */*)
                if [ "$original_state" = "1" ]; then
                    out=$(run_timeout 10 pm enable --user 0 "$component" 2>&1)
                else
                    out=$(run_timeout 10 pm default-state --user 0 "$component" 2>&1)
                fi
                command_status=$?
                log_line "restore component $component: $out"
                if [ "$command_status" -ne 0 ]; then
                    case "$out" in
                        *"Unknown component"*|*"Unknown target component"*|*"not found"*)
                            rm -f "$marker" 2>/dev/null
                            log_line "owned component no longer exists; marker cleared: $component"
                            continue
                            ;;
                    esac
                    failures=$((failures + 1))
                    log_line "WARN component restore failed; ownership marker retained: $component"
                    continue
                fi
                ;;
            *)
                failures=$((failures + 1))
                log_line "WARN malformed component marker retained: $marker"
                continue
                ;;
        esac
        rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
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
                else
                    log_line "WARN owned unsuspend unverified; marker retained: $pkg (pm=$pm_status dumpsys=$after_status): $out"
                    failures=$((failures + 1))
                fi
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
            case "$verify" in
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
                failures=$((failures + 1))
                log_line "WARN malformed AppOps marker retained: $pkg ($mode)"
                continue
                ;;
        esac
        out=$(run_timeout 5 appops set --user 0 "$pkg" \
            RUN_ANY_IN_BACKGROUND "$mode" 2>&1)
        command_status=$?
        verify=$(run_timeout 5 appops get --user 0 "$pkg" \
            RUN_ANY_IN_BACKGROUND 2>&1)
        case "$verify" in
            *"$mode"*)
                if [ "$command_status" -eq 0 ]; then
                    rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
                    log_line "restore AppOps mode: $pkg -> $mode"
                else
                    failures=$((failures + 1))
                    log_line "WARN AppOps restore failed; marker retained: $pkg ($out)"
                fi
                ;;
            *)
                failures=$((failures + 1))
                log_line "WARN AppOps restore unverified; marker retained: $pkg"
                ;;
        esac
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
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
                failures=$((failures + 1))
                log_line "WARN malformed standby marker retained: $pkg ($bucket)"
                continue
                ;;
        esac
        out=$(run_timeout 5 am set-standby-bucket --user 0 "$pkg" "$bucket" 2>&1)
        command_status=$?
        verify=$(run_timeout 5 am get-standby-bucket --user 0 "$pkg" 2>&1)
        case "$verify" in
            *"$bucket"*)
                if [ "$command_status" -eq 0 ]; then
                    rm -f "$marker" 2>/dev/null || failures=$((failures + 1))
                    log_line "restore standby bucket: $pkg -> $bucket"
                else
                    failures=$((failures + 1))
                    log_line "WARN standby restore failed; marker retained: $pkg ($out)"
                fi
                ;;
            *)
                failures=$((failures + 1))
                log_line "WARN standby restore unverified; marker retained: $pkg"
                ;;
        esac
    done
    rmdir "$marker_dir" 2>/dev/null
    sync 2>/dev/null
    [ "$failures" -eq 0 ]
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

    # Rescue is deliberately broader than the normal 15-target safety set:
    # unsuspend every package the system reports as suspended, then enable the
    # Nemu disable-user targets. This is the "unfreeze everything" path.
    if [ "$CURRENT_MODE" != "post-fs-data" ]; then
        unsuspend_status=0
        enable_status=0
        component_status=0
        hidden_status=0
        appops_status=0
        standby_status=0
        restore_owned_components || component_status=$?
        restore_owned_hidden || hidden_status=$?
        restore_owned_appops || appops_status=$?
        restore_owned_standby || standby_status=$?
        unsuspend_all || unsuspend_status=$?
        enable_disabled_targets || enable_status=$?
        if [ "$unsuspend_status" -ne 0 ] || [ "$enable_status" -ne 0 ] ||
            [ "$component_status" -ne 0 ] || [ "$hidden_status" -ne 0 ] ||
            [ "$appops_status" -ne 0 ] || [ "$standby_status" -ne 0 ]; then
            log_line "RESCUE incomplete (components=$component_status hidden=$hidden_status appops=$appops_status standby=$standby_status unsuspend=$unsuspend_status enable=$enable_status); ownership markers retained where unverified"
        else
            log_line "RESCUE recovery commands verified"
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
            # pm/dumpsys may not be ready at the early service stage. Run a
            # bounded recovery pass now, wait for boot_completed, then repeat
            # so a rescue is not lost to a one-shot failure.
            restore_owned_components ||
                log_line "WARN rescue component restore pass 1 incomplete"
            restore_owned_hidden ||
                log_line "WARN rescue hidden restore pass 1 incomplete"
            restore_owned_appops ||
                log_line "WARN rescue AppOps restore pass 1 incomplete"
            restore_owned_standby ||
                log_line "WARN rescue standby restore pass 1 incomplete"
            unsuspend_all || log_line "WARN rescue unsuspend pass 1 incomplete"
            enable_disabled_targets ||
                log_line "WARN rescue enable pass 1 incomplete"
            if wait_boot_completed; then
                restore_owned_components ||
                    log_line "WARN rescue component restore pass 2 incomplete"
                restore_owned_hidden ||
                    log_line "WARN rescue hidden restore pass 2 incomplete"
                restore_owned_appops ||
                    log_line "WARN rescue AppOps restore pass 2 incomplete"
                restore_owned_standby ||
                    log_line "WARN rescue standby restore pass 2 incomplete"
                unsuspend_all || log_line "WARN rescue unsuspend pass 2 incomplete"
                enable_disabled_targets ||
                    log_line "WARN rescue enable pass 2 incomplete"
            else
                log_line "WARN rescue: boot_completed timeout before retry"
            fi
            exit 0
        fi
        # Early safety pass, before waiting for boot_completed. Components and
        # hidden state are restored here too; the app re-applies its policy
        # after the boot delay, so early boot never depends on a disabled
        # component or an invisible package.
        restore_owned_components
        restore_owned_hidden
        # Do not unsuspend every suspended package on a normal boot: Hail/MDM
        # or the user may own that suspension. Only packages with Nemu's own
        # `suspended_by_nemu` marker are restored; rescue keeps the broad pass.
        unsuspend_owned || log_line "WARN owned unsuspend pass 1 incomplete"
        if wait_boot_completed; then
            restore_owned_components
            restore_owned_hidden
            unsuspend_owned || log_line "WARN owned unsuspend pass 2 incomplete"
            arm_success_watchdog
        else
            log_line "boot_completed timeout; not starting watchdog"
        fi
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
        if ! restore_owned_components; then
            log_line "rescue clear refused: one or more Nemu-owned components could not be restored"
            exit 1
        fi
        if ! restore_owned_hidden; then
            log_line "rescue clear refused: one or more Nemu-hidden packages could not be unhidden"
            exit 1
        fi
        if ! restore_owned_appops; then
            log_line "rescue clear refused: one or more AppOps modes could not be restored"
            exit 1
        fi
        if ! restore_owned_standby; then
            log_line "rescue clear refused: one or more standby buckets could not be restored"
            exit 1
        fi
        if ! unsuspend_owned; then
            log_line "rescue clear refused: one or more Nemu-owned suspensions could not be cleared"
            exit 1
        fi
        log_line "clear: Nemu-owned suspensions cleared; foreign suspensions are not touched"
        if ! enable_disabled_targets; then
            log_line "rescue clear refused: one or more Nemu-disabled packages remain disabled"
            exit 1
        fi
        for d in "$MODULE_DIR" "$MODULE_UPDATE_DIR"; do
            rm -f "$d/disable" 2>/dev/null || {
                log_line "rescue clear refused: could not re-enable module at $d"
                exit 1
            }
        done
        rm -f "$ATTEMPTS_FILE" "$BOOT_ID_FILE" "$GUARD_DIR/last_success" \
            "$CLEAN_SHUTDOWN_FILE" 2>/dev/null || {
            log_line "rescue clear refused: could not clear guard counters"
            exit 1
        }
        rm -f "$RESCUE_FILE" 2>/dev/null || {
            log_line "rescue clear refused: could not clear rescue flag"
            exit 1
        }
        log_line "rescue cleared by user"
        ;;
    *)
        echo "usage: $0 {post-fs-data|service|rescue|success|status|clear}" >&2
        exit 2
        ;;
esac
