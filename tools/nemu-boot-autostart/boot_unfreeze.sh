#!/system/bin/sh
# Run once after sys.boot_completed, before Nemu's boot trigger.
#
# Replays the app-generated ownership restore script so an unclean reboot
# (adb reboot, power loss, kernel crash) cannot leave Nemu-owned suspensions,
# components, hidden packages or orphan restrictions behind. The restore
# script is idempotent and scoped to Nemu's own markers; it never touches a
# suspension owned by Hail/MDM/the user.
#
# Overridable only for testing with a throwaway script.
SCRIPT=${NEMU_RESTORE_SCRIPT:-/data/adb/nemu/restore_frozen.sh}
LOG=${NEMU_BOOT_UNFREEZE_LOG:-/data/adb/nemu/boot_unfreeze.log}

[ -s "$SCRIPT" ] || exit 0

LOG_DIR=${LOG%/*}
mkdir -p "$LOG_DIR" 2>/dev/null
chmod 700 "$LOG_DIR" 2>/dev/null

{
    echo "$(date '+%Y-%m-%dT%H:%M:%S') boot unfreeze start"
    if command -v timeout >/dev/null 2>&1; then
        timeout 120 /system/bin/sh "$SCRIPT"
        status=$?
    else
        /system/bin/sh "$SCRIPT"
        status=$?
    fi
    echo "$(date '+%Y-%m-%dT%H:%M:%S') boot unfreeze exit=$status"
} >> "$LOG" 2>&1

# Bound the log without truncating before the restore commands ran.
tail -n 200 "$LOG" > "$LOG.tmp" 2>/dev/null &&
    mv -f "$LOG.tmp" "$LOG" 2>/dev/null
chmod 600 "$LOG" 2>/dev/null
sync 2>/dev/null
exit "$status"
