#!/system/bin/sh
# Remove only the persistent hooks installed by Nemu. Keep the guard state
# directory and log so removal does not erase diagnostic evidence.
HOOK=/data/adb/nemu/boot_guard/boot_guard.sh
RUNNER=/data/adb/nemu/boot_guard/boot_guard_runner.sh

remove_owned_hook() {
    script=$1
    mode=$2
    [ -f "$script" ] || return 0
    lines=$(wc -l < "$script" 2>/dev/null | tr -d ' ')
    first=$(sed -n '1p' "$script" 2>/dev/null)
    second=$(sed -n '2p' "$script" 2>/dev/null)
    if [ "$lines" = "2" ] &&
        [ "$first" = "#!/system/bin/sh" ] &&
        { [ "$second" = "exec $HOOK $mode" ] ||
            [ "$second" = "exec $RUNNER $mode" ]; }; then
        rm -f "$script"
    fi
}

remove_owned_hook /data/adb/post-fs-data.d/00nemu_boot_guard.sh post-fs-data
remove_owned_hook /data/adb/service.d/00nemu_boot_guard.sh service

# The guard directory has a dedicated Nemu path. Check its ownership marker
# before removing the installed copy in case the file was replaced manually.
if [ -f "$HOOK" ] &&
    grep -Fqx 'NEMU_GUARD_OWNER=nemu_boot_autostart' "$HOOK" 2>/dev/null; then
    rm -f "$HOOK"
fi
if [ -f "$RUNNER" ] &&
    grep -Fqx 'NEMU_GUARD_RUNNER_OWNER=nemu_boot_autostart' "$RUNNER" 2>/dev/null; then
    rm -f "$RUNNER"
fi

# Stop the root shutdown daemon and remove its runtime files; otherwise it
# keeps running until reboot and may call a deleted restore wrapper. The
# process is `sh .../shutdown_daemon.sh`, not a binary named shutdown_daemon,
# so match the script path/name with pkill -f.
pkill -f 'shutdown_daemon\.sh' 2>/dev/null ||
    killall -q shutdown_daemon.sh 2>/dev/null || true
sleep 1
rm -f /data/adb/nemu/shutdown.pipe /data/adb/nemu/restore_frozen.sh \
    /data/adb/nemu/restore_done
