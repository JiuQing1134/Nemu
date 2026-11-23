#!/system/bin/sh
# Root shutdown daemon. Two independent triggers:
# 1) a FIFO that a LSPosed system_server hook writes to just before shutdown;
# 2) a low-frequency poll of sys.powerctl while a restore script exists.
# Both run /data/adb/nemu/restore_frozen.sh, which the app regenerates after
# every freeze/restore. The daemon itself uses no CPU while blocked on the FIFO.
MODDIR=${0%/*}
NEMU_DIR=/data/adb/nemu
RESTORE="$NEMU_DIR/restore_frozen.sh"
PIPE="$NEMU_DIR/shutdown.pipe"

mkdir -p "$NEMU_DIR"
chmod 755 "$NEMU_DIR" 2>/dev/null
if [ ! -p "$PIPE" ]; then
    rm -f "$PIPE"
    mkfifo "$PIPE" 2>/dev/null
    chown root:system "$PIPE" 2>/dev/null
    chmod 0620 "$PIPE" 2>/dev/null
fi

# initrc `on shutdown` is the primary path. This poll is the fallback for
# late-load kernels where initrc injection is unavailable, and only runs while
# the restore script is non-empty (i.e. while something is frozen).
(
    while true; do
        if [ -s "$RESTORE" ]; then
            powerctl=$(getprop sys.powerctl 2>/dev/null)
            if [ -n "$powerctl" ]; then
                /system/bin/sh "$MODDIR/restore_if_frozen.sh"
                sleep 5
            fi
        fi
        sleep 1
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
        /system/bin/sh "$MODDIR/restore_if_frozen.sh"
    fi
done
