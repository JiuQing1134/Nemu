#!/system/bin/sh
# Runs the app-generated restore script. Safe when it does not exist or is
# empty. Called by init `on shutdown` and by shutdown_daemon.sh.
SCRIPT=/data/adb/nemu/restore_frozen.sh
if [ -s "$SCRIPT" ]; then
    /system/bin/sh "$SCRIPT"
    # ACK for the LSPosed shutdown hook: it waits briefly so the pipe writer
    # does not race `sys.powerctl`. Harmless when no hook is installed.
    : > /data/adb/nemu/restore_done
fi
