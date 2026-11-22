#!/system/bin/sh
# FinaSU/KernelSU late-boot service script.
# Installs the persistent auto-unbrick guard, then clears residual suspensions
# before starting Nemu. The guard also creates a rescue flag and disables this
# module after repeated unsuccessful boots (see boot_guard.sh / docs/09).

MODDIR=${0%/*}
. "$MODDIR/install_guard.sh"
install_nemu_guard

GUARD_DIR=${NEMU_GUARD_DIR:-/data/adb/nemu/boot_guard}
GUARD="$GUARD_DIR/boot_guard.sh"

run_guard() {
    if command -v timeout >/dev/null 2>&1; then
        # The guard itself bounds pm/dumpsys; this outer timeout also prevents
        # a service-stage hang from blocking the Nemu launch forever.
        timeout 240 "$GUARD" "$@"
    else
        "$GUARD" "$@" &
        guard_pid=$!
        (
            sleep 240
            kill "$guard_pid" 2>/dev/null
        ) >/dev/null 2>&1 &
        timer_pid=$!
        wait "$guard_pid"
        guard_status=$?
        kill "$timer_pid" 2>/dev/null
        wait "$timer_pid" 2>/dev/null
        return "$guard_status"
    fi
}

if [ -x "$GUARD" ]; then
    if [ -f "$GUARD_DIR/rescue.flag" ]; then
        run_guard rescue
        exit 0
    fi
    run_guard service
    [ -f "$GUARD_DIR/rescue.flag" ] && exit 0
fi

# Root shutdown daemon: FIFO trigger for the LSPosed system_server hook plus a
# sys.powerctl fallback. It reads the app-generated restore script, so it works
# even when the Nemu process is not running.
if [ -f "$MODDIR/shutdown_daemon.sh" ]; then
    chmod 755 "$MODDIR/shutdown_daemon.sh" "$MODDIR/restore_if_frozen.sh" 2>/dev/null
    nohup /system/bin/sh "$MODDIR/shutdown_daemon.sh" >/dev/null 2>&1 &
fi

# Never launch Nemu before boot completion.
if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
    exit 0
fi

sleep 5
am start -n com.nemu.dream/com.nemu.hibernate.MainActivity >/dev/null 2>&1

# Fallback only when the guard is unavailable. Do not run a second guard pass
# after starting the app.
if [ ! -x "$GUARD" ]; then
    pm unsuspend --user 0 \
        com.hihonor.ouc \
        com.honor.yoyoappsug \
        com.honor.yoyocards \
        com.hihonor.suggestion \
        com.hihonor.sceneservice \
        com.hihonor.hiview \
        com.hihonor.hiviewpush \
        com.hihonor.hiviewtunnel \
        com.hihonor.numberidentity \
        com.hihonor.aipluginengine \
        com.hihonor.assistant \
        com.hihonor.magicvoice \
        com.hihonor.voiceengine \
        com.hihonor.brain \
        com.hihonor.mageruntime \
        com.hihonor.systemappsupdater \
        com.hihonor.visionengine \
        com.hihonor.magictranslate \
        com.hihonor.magicavatar \
        com.hihonor.lens \
        com.hihonor.hiboard \
        com.hihonor.magichome \
        com.hihonor.detectrepair \
        com.hihonor.cipservice \
        com.hihonor.assetsync \
        com.hihonor.assetsyncservice \
        com.hihonor.android.hnaps \
        com.hihonor.msdp \
        com.hihonor.securityserver \
        com.hihonor.awareness >/dev/null 2>&1
fi
