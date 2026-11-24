#!/system/bin/sh
# Root shutdown daemon. Two independent triggers:
# 1) a FIFO that a LSPosed system_server hook writes to just before shutdown;
# 2) an unconditional 2-second poll of sys.powerctl.
# A user-requested reason is recorded as a planned shutdown (the boot guard
# then does not count this boot as a failed start); watchdog/thermal reasons
# are not. The restore script is replayed once per distinct powerctl value.
# The FIFO reader blocks; the 2s poll is the only periodic work.
MODDIR=${0%/*}
NEMU_DIR=/data/adb/nemu
GUARD_DIR="$NEMU_DIR/boot_guard"
PLANNED_FILE="$GUARD_DIR/planned_shutdown"
RESTORE="$NEMU_DIR/restore_frozen.sh"
PIPE="$NEMU_DIR/shutdown.pipe"

is_planned_powerctl() {
    # Only a user-requested reboot/poweroff resets the failed-boot counter.
    # Watchdog, thermal and kernel panic reasons must still count as failures.
    value=$1
    [ -n "$value" ] || return 1
    case "$value" in
        *,*) reason=${value#*,} ;;
        reboot|shutdown) reason= ;;
        *) return 1 ;;
    esac
    case "$reason" in
        ''|userrequested|shell|recovery|bootloader|fastboot|sideload)
            return 0 ;;
        *) return 1 ;;
    esac
}

mark_planned_shutdown() {
    # Atomic write: a concurrent post-fs-data guard must never read a
    # half-written boot_id. Content is "<boot_id> <epoch>"; the App uses the
    # epoch to decide whether the shutdown followed its last freeze attempt.
    mkdir -p "$GUARD_DIR" 2>/dev/null
    chmod 700 "$GUARD_DIR" 2>/dev/null
    boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$boot_id" ] || boot_id=$(date '+%s' 2>/dev/null)
    epoch=$(date '+%s' 2>/dev/null)
    # Keep one unconsumed marker in a previous slot. If guard did not run on
    # the boot after that marker, the next shutdown would otherwise overwrite
    # it and lose the counter reset.
    old_raw=$(cat "$PLANNED_FILE" 2>/dev/null)
    old_boot=${old_raw%% *}
    if [ -n "$old_boot" ] && [ "$old_boot" != "$boot_id" ]; then
        prev_tmp="$PLANNED_FILE.prev.$$"
        if cp "$PLANNED_FILE" "$prev_tmp" 2>/dev/null; then
            chmod 600 "$prev_tmp" 2>/dev/null
            mv -f "$prev_tmp" "$PLANNED_FILE.prev" 2>/dev/null ||
                rm -f "$prev_tmp" 2>/dev/null
        fi
    fi
    planned_tmp="$PLANNED_FILE.tmp.$$"
    if printf '%s %s\n' "$boot_id" "${epoch:-0}" > "$planned_tmp" 2>/dev/null; then
        chmod 600 "$planned_tmp" 2>/dev/null
        mv -f "$planned_tmp" "$PLANNED_FILE" 2>/dev/null ||
            rm -f "$planned_tmp" 2>/dev/null
    fi
    chmod 600 "$PLANNED_FILE" 2>/dev/null
}

mkdir -p "$NEMU_DIR"
chmod 755 "$NEMU_DIR" 2>/dev/null
if [ ! -p "$PIPE" ]; then
    rm -f "$PIPE"
    mkfifo "$PIPE" 2>/dev/null
    chown root:system "$PIPE" 2>/dev/null
    chmod 0620 "$PIPE" 2>/dev/null
fi

# initrc `on shutdown` is the primary path. This poll is the fallback for
# late-load kernels where initrc injection is unavailable. sys.powerctl is
# read unconditionally every 2 seconds: a planned shutdown must be recorded
# even when nothing is frozen. last_powerctl makes the trigger one-shot per
# distinct value so the shutdown window does not replay the restore script
# every second. The reason whitelist keeps watchdog/thermal reboots counted as
# failed boots; those still run the idempotent restore script.
(
    last_powerctl=
    while true; do
        powerctl=$(getprop sys.powerctl 2>/dev/null)
        if [ -n "$powerctl" ] && [ "$powerctl" != "$last_powerctl" ]; then
            last_powerctl=$powerctl
            if is_planned_powerctl "$powerctl"; then
                mark_planned_shutdown
            fi
            if [ -s "$RESTORE" ]; then
                /system/bin/sh "$MODDIR/restore_if_frozen.sh"
            fi
        fi
        sleep 2
    done
) >/dev/null 2>&1 &

while true; do
    if [ ! -p "$PIPE" ]; then
        rm -f "$PIPE"
        mkfifo "$PIPE" 2>/dev/null
        chown root:system "$PIPE" 2>/dev/null
        chmod 0620 "$PIPE" 2>/dev/null
        sleep 2
        continue
    fi
    if read -r _token < "$PIPE"; then
        # The hook may fire just before init sets sys.powerctl. Mark planned
        # only when the reason is a user request; otherwise the later poll can
        # still classify it, and a watchdog reboot stays a failed boot.
        powerctl=$(getprop sys.powerctl 2>/dev/null)
        if is_planned_powerctl "$powerctl"; then
            mark_planned_shutdown
        fi
        /system/bin/sh "$MODDIR/restore_if_frozen.sh"
    fi
done
