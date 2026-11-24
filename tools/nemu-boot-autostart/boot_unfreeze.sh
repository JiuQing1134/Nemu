#!/system/bin/sh
# Restores every Nemu-owned freeze state as early as PackageManager allows.
#
# The installed guard's `early` mode is the primary pass: it restores
# Nemu-owned disable-user, hidden (pseudo-uninstall), disabled components,
# AppOps and standby buckets from its ownership markers and takes
# /data/adb/nemu/restore.lock itself. The app-generated restore_frozen.sh is
# replayed afterwards when present, under the same lock. The guard pass never
# depends on that file: an empty or missing script cannot block recovery.
#
# Modes:
#   --wait-pm : started at the early service stage. Wait until PackageManager's
#               binder answers, then restore immediately -- before the launcher
#               reaches the desktop and before sys.boot_completed.
#   (default) : final verification pass after boot_completed, before the app
#               boot trigger.
#
# All pm work is idempotent and scoped to Nemu's own markers; a suspension
# owned by Hail/MDM/the user is never touched. Paths are overridable only for
# local testing.
SCRIPT=${NEMU_RESTORE_SCRIPT:-/data/adb/nemu/restore_frozen.sh}
LOG=${NEMU_BOOT_UNFREEZE_LOG:-/data/adb/nemu/boot_unfreeze.log}
NEMU_DIR=${NEMU_DIR:-/data/adb/nemu}
GUARD=${NEMU_GUARD:-/data/adb/nemu/boot_guard/boot_guard.sh}
RESTORE_LOCK=${NEMU_RESTORE_LOCK_DIR:-"$NEMU_DIR/restore.lock"}
WAIT_PM=0
MODE=final
if [ "$1" = "--wait-pm" ]; then
    WAIT_PM=1
    MODE=early
fi

LOG_DIR=${LOG%/*}
mkdir -p "$LOG_DIR" 2>/dev/null
mkdir -p "$NEMU_DIR" 2>/dev/null
chmod 700 "$LOG_DIR" 2>/dev/null

log_line() {
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$*" \
        >> "$LOG" 2>&1
    chmod 600 "$LOG" 2>/dev/null
}

run_timeout() {
    seconds=$1
    shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$seconds" "$@"
    else
        # Android builds without toybox timeout still get a bounded wait.
        "$@" &
        timed_pid=$!
        (
            sleep "$seconds"
            kill "$timed_pid" 2>/dev/null
        ) >/dev/null 2>&1 &
        timer_pid=$!
        wait "$timed_pid"
        command_status=$?
        kill "$timer_pid" 2>/dev/null
        wait "$timer_pid" 2>/dev/null
        return "$command_status"
    fi
}

pm_ready() {
    # Bounded probe: a stuck system_server must not hang early boot. Only a
    # real `package:` line proves the package service answers.
    pm_list=$(run_timeout 3 /system/bin/cmd package list packages 2>/dev/null)
    printf '%s\n' "$pm_list" | grep -q '^package:'
}

boot_id() {
    if [ -n "${NEMU_BOOT_ID:-}" ]; then
        printf '%s\n' "$NEMU_BOOT_ID"
        return
    fi
    cat /proc/sys/kernel/random/boot_id 2>/dev/null || date '+%s'
}

lock_owner_pid() {
    if [ -d "$RESTORE_LOCK" ]; then
        if [ -f "$RESTORE_LOCK/pid" ]; then
            cat "$RESTORE_LOCK/pid" 2>/dev/null
            return
        fi
        # Tolerate an alternate in-directory layout: take the first numeric
        # field found in the lock's own metadata files.
        for lock_file in "$RESTORE_LOCK"/*; do
            [ -f "$lock_file" ] || continue
            awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) { print $i; exit } }' \
                "$lock_file" 2>/dev/null
        done | head -n 1
    elif [ -f "$RESTORE_LOCK" ]; then
        # Tolerate a single-file lock from an older guard build; the first
        # numeric field is the owner pid.
        awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) { print $i; exit } }' \
            "$RESTORE_LOCK" 2>/dev/null
    fi
}

lock_owner_boot() {
    if [ -d "$RESTORE_LOCK" ]; then
        if [ -f "$RESTORE_LOCK/boot_id" ]; then
            cat "$RESTORE_LOCK/boot_id" 2>/dev/null
            return
        fi
        # Same tolerance: the boot id is a UUID-shaped field in any of the
        # lock's own metadata files.
        for lock_file in "$RESTORE_LOCK"/*; do
            [ -f "$lock_file" ] || continue
            awk '{
                for (i = 1; i <= NF; i++)
                    if (length($i) == 36 && $i ~ /^[0-9a-fA-F-]+$/) {
                        print $i
                        exit
                    }
            }' "$lock_file" 2>/dev/null
        done | head -n 1
    elif [ -f "$RESTORE_LOCK" ]; then
        awk '{
            for (i = 1; i <= NF; i++)
                if (length($i) == 36 && $i ~ /^[0-9a-fA-F-]+$/) {
                    print $i
                    exit
                }
        }' "$RESTORE_LOCK" 2>/dev/null
    fi
}

acquire_restore_lock() {
    # Same protocol as boot_guard.sh: /data/adb/nemu/restore.lock is a
    # directory holding `pid` and `boot_id`. Wait up to 60 s; evict a lock
    # whose boot_id belongs to a previous boot or whose pid no longer exists.
    lock_boot_now=$(boot_id)
    lock_wait=0
    while :; do
        if [ -e "$RESTORE_LOCK" ]; then
            owner_boot=$(lock_owner_boot)
            owner_pid=$(lock_owner_pid)
            lock_stale=0
            [ -n "$owner_boot" ] && [ "$owner_boot" != "$lock_boot_now" ] &&
                lock_stale=1
            # Same rule as boot_guard.sh: a present boot_id with a missing or
            # dead pid is stale; a pid-only lock is reclaimed when dead.
            if [ -n "$owner_boot" ] || [ -n "$owner_pid" ]; then
                case "$owner_pid" in
                    ''|*[!0-9]*) lock_stale=1 ;;
                    *)
                        kill -0 "$owner_pid" 2>/dev/null || lock_stale=1
                        ;;
                esac
            fi
            # An empty lock gets a short grace period so a concurrent creator
            # writing pid/boot_id is not evicted mid-write.
            if [ -z "$owner_boot" ] && [ -z "$owner_pid" ] &&
                [ "$lock_wait" -ge 5 ]; then
                lock_stale=1
            fi
            [ "$lock_stale" = "1" ] && rm -rf "$RESTORE_LOCK" 2>/dev/null
        fi
        if mkdir "$RESTORE_LOCK" 2>/dev/null; then
            printf '%s\n' "$$" > "$RESTORE_LOCK/pid" 2>/dev/null
            printf '%s\n' "$lock_boot_now" > "$RESTORE_LOCK/boot_id" 2>/dev/null
            chmod 700 "$RESTORE_LOCK" 2>/dev/null
            chmod 600 "$RESTORE_LOCK/pid" "$RESTORE_LOCK/boot_id" 2>/dev/null
            return 0
        fi
        [ "$lock_wait" -ge 60 ] && return 1
        sleep 1
        lock_wait=$((lock_wait + 1))
    done
}

release_restore_lock() {
    # Only the owner removes the lock; a stale lock already replaced by
    # another process must not be deleted by us.
    if [ -d "$RESTORE_LOCK" ] &&
        [ "$(cat "$RESTORE_LOCK/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$RESTORE_LOCK" 2>/dev/null
    fi
}

log_line "boot unfreeze $MODE start" \
    "(boot_completed=$(getprop sys.boot_completed 2>/dev/null))"

# Wait for the package service before touching pm, but never forever: a stuck
# system_server must fall through to the next boot stage instead of hanging.
if [ "$WAIT_PM" = "1" ]; then
    i=0
    while [ "$i" -lt 90 ]; do
        if pm_ready; then
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    if [ "$i" -ge 90 ]; then
        log_line "boot unfreeze $MODE: PackageManager not ready after ${i}s"
        exit 1
    fi
    log_line "boot unfreeze $MODE: PackageManager ready after ${i}s"
fi

# Ownership-marker restore first. The guard takes restore.lock itself around
# its pm work, so no outer lock is held here.
guard_status=skipped
exit_status=0
if [ -x "$GUARD" ]; then
    run_timeout 120 "$GUARD" early
    guard_status=$?
    [ "$guard_status" -ne 0 ] && exit_status=1
    log_line "boot unfreeze $MODE guard early exit=$guard_status"
    if [ "$guard_status" -eq 0 ]; then
        # Ack this boot's early pass so boot_guard.sh service does not repeat
        # the full scan when it runs from service.d in parallel.
        mkdir -p "$NEMU_DIR/boot_guard" 2>/dev/null
        early_tmp="$NEMU_DIR/boot_guard/early_done.$$"
        if printf '%s\n' "$(boot_id)" > "$early_tmp" 2>/dev/null; then
            chmod 600 "$early_tmp" 2>/dev/null
            mv -f "$early_tmp" "$NEMU_DIR/boot_guard/early_done" 2>/dev/null ||
                rm -f "$early_tmp" 2>/dev/null
        fi
    fi
else
    guard_status=missing
    log_line "boot unfreeze $MODE guard not executable: $GUARD"
fi

# Then replay the app-generated script, if and only if it has content. An
# empty/missing script must not prevent the guard pass above from running.
script_status=skipped
if [ -s "$SCRIPT" ]; then
    if acquire_restore_lock; then
        # Keep the generated script's per-command FAIL lines in the boot log;
        # the exit code alone cannot say which package failed.
        run_timeout 120 /system/bin/sh "$SCRIPT" >>"$LOG" 2>&1
        script_status=$?
        [ "$script_status" -ne 0 ] && exit_status=1
        release_restore_lock
        log_line "boot unfreeze $MODE script exit=$script_status"
    else
        script_status=lock-timeout
        exit_status=1
        log_line "boot unfreeze $MODE restore.lock timeout; script skipped"
    fi
else
    log_line "boot unfreeze $MODE script empty or missing; guard pass only"
fi

log_line "boot unfreeze $MODE done" \
    "(boot_completed=$(getprop sys.boot_completed 2>/dev/null)" \
    "guard=$guard_status script=$script_status)"

# Bound the log without truncating before the restore commands ran. The temp
# name is pid-unique so overlapping early/final passes cannot clobber each
# other's trim file.
LOG_TMP="$LOG.tmp.$$"
tail -n 200 "$LOG" > "$LOG_TMP" 2>/dev/null &&
    mv -f "$LOG_TMP" "$LOG" 2>/dev/null
rm -f "$LOG_TMP" 2>/dev/null
chmod 600 "$LOG" 2>/dev/null
sync 2>/dev/null
exit "$exit_status"
