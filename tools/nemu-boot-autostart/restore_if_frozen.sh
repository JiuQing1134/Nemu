#!/system/bin/sh
# Runs the app-generated restore script. Safe when it does not exist or is
# empty. Called by init `on shutdown` and by shutdown_daemon.sh.
NEMU_DIR=/data/adb/nemu
GUARD_DIR="$NEMU_DIR/boot_guard"
PLANNED_FILE="$GUARD_DIR/planned_shutdown"
SCRIPT="$NEMU_DIR/restore_frozen.sh"

is_planned_powerctl() {
    # Mirror shutdown_daemon.sh: watchdog/thermal reboots keep the failure
    # counter; only user-requested shutdown/reboot resets it.
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

# Record a planned shutdown before restoring so the boot guard does not count
# the next start as a failed boot after quick adb reboots. Atomic write via a
# pid-unique temp file; watchdog/thermal reasons are deliberately not marked.
powerctl=$(getprop sys.powerctl 2>/dev/null)
if is_planned_powerctl "$powerctl"; then
    mkdir -p "$GUARD_DIR" 2>/dev/null
    chmod 700 "$GUARD_DIR" 2>/dev/null
    boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
    [ -n "$boot_id" ] || boot_id=$(date '+%s' 2>/dev/null)
    epoch=$(date '+%s' 2>/dev/null)
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
fi

if [ -s "$SCRIPT" ]; then
    /system/bin/sh "$SCRIPT"
    # ACK for the LSPosed shutdown hook: it waits briefly so the pipe writer
    # does not race `sys.powerctl`. Harmless when no hook is installed.
    : > /data/adb/nemu/restore_done
    # The generated script now counts per-command failures and exits with the
    # count, but it is still not truncated here: the app clears it only after
    # its own verified shutdown pass, and replaying an idempotent script is
    # safer than losing a restore that failed earlier.
fi
