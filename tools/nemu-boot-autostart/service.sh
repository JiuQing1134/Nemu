#!/system/bin/sh
# FinaSU/KernelSU late-boot service script.
# Installs the persistent auto-unbrick guard, then clears residual suspensions.
# The guard also creates a rescue flag and disables this module after repeated
# unsuccessful boots (see boot_guard.sh / docs/09).
# It deliberately does NOT launch the Nemu UI; boot recovery is handled by the
# app's own BootReceiver/WorkManager so the user never sees an app pop up.

MODDIR=${0%/*}
. "$MODDIR/install_guard.sh"
install_nemu_guard

# Earliest supported unfreeze: as soon as PackageManager's binder answers,
# replay Nemu's ownership restore script. This runs before the launcher/desktop
# and before sys.boot_completed, so a frozen system app cannot keep the device
# stuck on the boot screen. The final pass runs again after boot_completed.
if [ -f "$MODDIR/boot_unfreeze.sh" ]; then
    nohup /system/bin/sh "$MODDIR/boot_unfreeze.sh" --wait-pm \
        >/dev/null 2>&1 &
fi

# HONOR does not deliver BOOT_COMPLETED to Nemu, and LOCKED_BOOT_COMPLETED can
# be missed if the app process dies before unlock. Ping the app once the
# system is actually up so its BootReceiver can run the safety pass. The
# receiver is protected by android.permission.DUMP (root/shell only).
trigger_nemu_after_boot() {
    i=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] &&
        [ "$i" -lt 90 ]; do
        sleep 2
        i=$((i + 1))
    done
    [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] || return 0
    # Clear Nemu-owned state left by an unclean reboot before the app and the
    # boot-freeze plan run. This is the root-side "unfreeze once every boot"
    # safety pass; it is ownership-scoped and idempotent.
    if [ -f "$MODDIR/boot_unfreeze.sh" ]; then
        /system/bin/sh "$MODDIR/boot_unfreeze.sh" || true
    fi
    am broadcast --user 0 \
        -a com.nemu.dream.action.BOOT_TRIGGER \
        -n com.nemu.dream/com.nemu.hibernate.policy.BootTriggerReceiver \
        >/dev/null 2>&1 || true
}

trigger_nemu_after_boot &

GUARD_DIR=${NEMU_GUARD_DIR:-/data/adb/nemu/boot_guard}
GUARD="$GUARD_DIR/boot_guard.sh"

run_guard() {
    if command -v timeout >/dev/null 2>&1; then
        # The guard itself bounds pm/dumpsys; this outer timeout also prevents
        # a service-stage hang from blocking the rest of boot forever.
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

# The module never opens the app UI. Boot recovery is performed by Nemu's
# BootReceiver/BootWorker after USER_UNLOCKED, without stealing the foreground.
# Fallback only when the guard is unavailable: wait (bounded) for boot
# completion so PackageManager is ready.
if [ ! -x "$GUARD" ]; then
    i=0
    while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] &&
        [ "$i" -lt 60 ]; do
        sleep 2
        i=$((i + 1))
    done
    if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
        exit 0
    fi
    for pkg in \
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
    com.hihonor.awareness; do

        pm unsuspend --user 0 "$pkg" >/dev/null 2>&1 || true
    done

fi
