#!/system/bin/sh
# Runs the app-generated restore script. Safe when it does not exist or is
# empty. Called by init `on shutdown` and by shutdown_daemon.sh.
SCRIPT=/data/adb/nemu/restore_frozen.sh
if [ -s "$SCRIPT" ]; then
    /system/bin/sh "$SCRIPT"
    # ACK for the LSPosed shutdown hook: it waits briefly so the pipe writer
    # does not race `sys.powerctl`. Harmless when no hook is installed.
    : > /data/adb/nemu/restore_done
    # Do not truncate on a bare exit 0: the generated script has no per-
    # command accounting, so one failed earlier line would be discarded while
    # later lines still make the shell exit 0. The app clears the script only
    # after its own verified shutdown pass; replaying an idempotent script is
    # safer than losing a failed restore.
fi
