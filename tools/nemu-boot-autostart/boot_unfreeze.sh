#!/system/bin/sh
# Replays the app-generated ownership restore script so an unclean reboot
# (adb reboot, power loss, kernel crash) cannot leave Nemu-owned suspensions,
# components, hidden packages or orphan restrictions behind.
#
# Modes:
#   --wait-pm : started at the early service stage. Wait until PackageManager's
#               binder answers, then restore immediately — before the launcher
#               reaches the desktop and before sys.boot_completed.
#   (default) : final verification pass after boot_completed, before the app
#               boot trigger.
#
# The restore script is idempotent and scoped to Nemu's own markers; it never
# touches a suspension owned by Hail/MDM/the user. Paths are overridable only
# for local testing.
SCRIPT=${NEMU_RESTORE_SCRIPT:-/data/adb/nemu/restore_frozen.sh}
LOG=${NEMU_BOOT_UNFREEZE_LOG:-/data/adb/nemu/boot_unfreeze.log}
WAIT_PM=0
MODE=final
if [ "$1" = "--wait-pm" ]; then
    WAIT_PM=1
    MODE=early
fi

[ -s "$SCRIPT" ] || exit 0

LOG_DIR=${LOG%/*}
mkdir -p "$LOG_DIR" 2>/dev/null
chmod 700 "$LOG_DIR" 2>/dev/null

if [ "$WAIT_PM" = "1" ]; then
    i=0
    while [ "$i" -lt 90 ]; do
        if /system/bin/cmd package list packages >/dev/null 2>&1; then
            break
        fi
        sleep 1
        i=$((i + 1))
    done
    if [ "$i" -ge 90 ]; then
        echo "$(date '+%Y-%m-%dT%H:%M:%S') boot unfreeze $MODE: " \
            "PackageManager not ready after ${i}s" >> "$LOG" 2>&1
        chmod 600 "$LOG" 2>/dev/null
        exit 1
    fi
fi

{
    echo "$(date '+%Y-%m-%dT%H:%M:%S') boot unfreeze $MODE start" \
        "(boot_completed=$(getprop sys.boot_completed 2>/dev/null))"
    if command -v timeout >/dev/null 2>&1; then
        timeout 120 /system/bin/sh "$SCRIPT"
        status=$?
    else
        /system/bin/sh "$SCRIPT"
        status=$?
    fi
    echo "$(date '+%Y-%m-%dT%H:%M:%S') boot unfreeze $MODE exit=$status" \
        "(boot_completed=$(getprop sys.boot_completed 2>/dev/null))"
} >> "$LOG" 2>&1

# Bound the log without truncating before the restore commands ran.
tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null &&
    mv -f "$LOG.tmp" "$LOG" 2>/dev/null
chmod 600 "$LOG" 2>/dev/null
sync 2>/dev/null
exit "$status"
