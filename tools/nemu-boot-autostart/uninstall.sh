#!/system/bin/sh
# Remove only the persistent hooks installed by Nemu. Keep the guard state
# directory and log so removal does not erase diagnostic evidence.
HOOK=/data/adb/nemu/boot_guard/boot_guard.sh
RUNNER=/data/adb/nemu/boot_guard/boot_guard_runner.sh
DEEP_DIR=/data/adb/nemu/deep_uninstall

# Restore deep-uninstalled packages before this module and its restore script
# disappear. deep_restore.sh --restore-only never re-uninstalls and removes its
# temporary state on exit. Fail-soft: an uninstall must still succeed if PM is
# unavailable or the manifest is missing.
module_dir=${MODDIR:-}
if [ -z "$module_dir" ]; then
    case "$0" in
        */*) module_dir=${0%/*} ;;
        *) module_dir=$(pwd 2>/dev/null) ;;
    esac
fi
# A boot-time deep_restore.sh may still hold .restore.lock while it waits for
# the re-uninstall window. Stop it and wait for the lock before the
# synchronous restore-only pass; otherwise restore-only exits silently.
pkill -f 'deep_restore\.sh' 2>/dev/null || true
i=0
while [ -d "$DEEP_DIR/.restore.lock" ] && [ "$i" -lt 10 ]; do
    sleep 1
    i=$((i + 1))
done
rm -rf "$DEEP_DIR/.restore.lock" 2>/dev/null

restore_script=
for candidate in "$module_dir" /data/adb/modules/nemu_boot_autostart; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate/deep_restore.sh" ]; then
        restore_script="$candidate/deep_restore.sh"
        break
    fi
done
if [ -n "$restore_script" ]; then
    /system/bin/sh "$restore_script" --restore-only >/dev/null 2>&1 || true
fi

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
# Stop any pending deep-uninstall re-uninstall pass and clear the capability
# marker plus pending state. Keep manifest.tsv, manifest.last.tsv and the APK
# backups: they describe the user's choice and stay valid across a reinstall.
pkill -f 'deep_restore\.sh' 2>/dev/null || true
rm -f "$DEEP_DIR/auto_restore_ready" \
    "$DEEP_DIR/restored_this_boot.tsv" \
    "$DEEP_DIR/manifest.boot.tsv" \
    "$DEEP_DIR"/.restore_only_state.* 2>/dev/null
rm -rf "$DEEP_DIR/.restore.lock" 2>/dev/null
sleep 1
rm -f /data/adb/nemu/shutdown.pipe /data/adb/nemu/restore_frozen.sh \
    /data/adb/nemu/restore_done
