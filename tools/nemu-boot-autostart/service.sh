#!/system/bin/sh
# FinaSU/KernelSU late-boot service script.
# Installs the persistent auto-unbrick guard, then clears residual freeze state.
# The guard also creates a rescue flag and disables this module after repeated
# unsuccessful boots (see boot_guard.sh / docs/09).
# It deliberately does NOT launch the Nemu UI; boot recovery is handled by the
# app's own BootReceiver/WorkManager so the user never sees an app pop up.

MODDIR=${0%/*}
GUARD_HOOK=/data/adb/service.d/00nemu_boot_guard.sh
GUARD_SCRIPT=/data/adb/nemu/boot_guard/boot_guard.sh
GUARD_RUNNER=/data/adb/nemu/boot_guard/boot_guard_runner.sh
# On the first install the hook is created during this service stage and may
# not be picked up until the next boot. Only skip our own service pass when
# the hook already existed AND the installed guard content did not change
# during this boot (module update still needs the new guard to run now).
hook_preexisting=0
if [ -x "$GUARD_HOOK" ] &&
    [ "$(sed -n '1p' "$GUARD_HOOK" 2>/dev/null)" = "#!/system/bin/sh" ] &&
    [ "$(sed -n '2p' "$GUARD_HOOK" 2>/dev/null)" = "exec $GUARD_RUNNER service" ]; then
    hook_preexisting=1
fi
hash_file() {
    if [ -f "$1" ] && command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" 2>/dev/null | awk '{print $1}'
    fi
}
guard_hash_before=$(hash_file "$GUARD_SCRIPT")
runner_hash_before=$(hash_file "$GUARD_RUNNER")
. "$MODDIR/install_guard.sh"
install_status=0
install_nemu_guard || install_status=1
guard_hash_after=$(hash_file "$GUARD_SCRIPT")
runner_hash_after=$(hash_file "$GUARD_RUNNER")
guard_changed=0
if [ "$install_status" -ne 0 ] ||
    [ -z "$guard_hash_before" ] || [ -z "$guard_hash_after" ] ||
    [ -z "$runner_hash_before" ] || [ -z "$runner_hash_after" ] ||
    [ "$guard_hash_before" != "$guard_hash_after" ] ||
    [ "$runner_hash_before" != "$runner_hash_after" ]; then
    guard_changed=1
fi

# Earliest supported unfreeze: as soon as PackageManager's binder answers,
# run the guard's early ownership pass (disable-user/hidden/suspend/
# components/AppOps/standby) and then replay Nemu's restore script. This runs
# before the launcher/desktop and before sys.boot_completed, so a frozen
# system app cannot keep the device stuck on the boot screen. The final pass
# runs again after boot_completed.
if [ -f "$MODDIR/boot_unfreeze.sh" ]; then
    if command -v nohup >/dev/null 2>&1; then
        nohup /system/bin/sh "$MODDIR/boot_unfreeze.sh" --wait-pm \
            >/dev/null 2>&1 &
    else
        # nohup is normally present; the plain background form keeps early
        # recovery alive on a reduced toybox build.
        /system/bin/sh "$MODDIR/boot_unfreeze.sh" --wait-pm \
            >/dev/null 2>&1 &
    fi
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
# A rescue pass can run two full pm sweeps (before and after boot_completed),
# so the outer budget must stay well above the old fixed 240 s. Overridable
# for local testing.
GUARD_TIMEOUT=${NEMU_GUARD_TIMEOUT:-480}
# A rescue can run unsuspend/restore scans both before and after
# boot_completed, so it gets its own, larger budget.
RESCUE_TIMEOUT=${NEMU_RESCUE_TIMEOUT:-1200}

log_guard_timeout() {
    mkdir -p "$GUARD_DIR" 2>/dev/null
    printf '%s guard timeout after %ss (mode=%s)\n' \
        "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "${2:-$GUARD_TIMEOUT}" \
        "${1:-unknown}" >> "$GUARD_DIR/guard.log" 2>/dev/null
    chmod 600 "$GUARD_DIR/guard.log" 2>/dev/null
    sync 2>/dev/null
}

run_guard() {
    guard_budget=$GUARD_TIMEOUT
    if [ "$1" = "rescue" ] || [ -f "$GUARD_DIR/rescue.flag" ]; then
        guard_budget=$RESCUE_TIMEOUT
    elif [ "$1" = "service" ]; then
        # A service-stage scan may cross the rescue threshold; give it the
        # rescue budget before it starts, not after it has already rescued.
        threshold=${NEMU_RESCUE_THRESHOLD:-3}
        attempts=$(cat "$GUARD_DIR/attempts" 2>/dev/null)
        case "$attempts" in ''|*[!0-9]*) attempts=0 ;; esac
        if [ $((attempts + 1)) -ge "$threshold" ]; then
            guard_budget=$RESCUE_TIMEOUT
        fi
    fi
    if command -v timeout >/dev/null 2>&1; then
        # The guard itself bounds pm/dumpsys; this outer timeout also prevents
        # a service-stage hang from blocking the rest of boot forever.
        timeout "$guard_budget" "$GUARD" "$@"
        guard_status=$?
        if [ "$guard_status" -eq 124 ]; then
            log_guard_timeout "$1" "$guard_budget"
        fi
        return "$guard_status"
    else
        "$GUARD" "$@" &
        guard_pid=$!
        timeout_marker="$GUARD_DIR/.guard_timeout.$$"
        rm -f "$timeout_marker" 2>/dev/null
        (
            sleep "$guard_budget"
            : > "$timeout_marker" 2>/dev/null
            kill "$guard_pid" 2>/dev/null
        ) >/dev/null 2>&1 &
        timer_pid=$!
        wait "$guard_pid"
        guard_status=$?
        kill "$timer_pid" 2>/dev/null
        wait "$timer_pid" 2>/dev/null
        if [ -f "$timeout_marker" ]; then
            rm -f "$timeout_marker" 2>/dev/null
            log_guard_timeout "$1" "$guard_budget"
        fi
        return "$guard_status"
    fi
}

if [ -x "$GUARD" ]; then
    if [ -f "$GUARD_DIR/rescue.flag" ]; then
        # The persistent service.d hook runs the same rescue if it was active
        # for this boot. On a first install/update it may not have run yet.
        if [ "$hook_preexisting" -eq 0 ] || [ "$guard_changed" -eq 1 ]; then
            run_guard rescue
        fi
        exit 0
    fi
    if [ "$hook_preexisting" -eq 1 ] && [ "$guard_changed" -eq 0 ]; then
        # The persistent hook already starts the same guard content; running
        # it here too would duplicate the full marker scan and lock contention.
        :
    else
        run_guard service
        [ -f "$GUARD_DIR/rescue.flag" ] && exit 0
    fi
fi

# Root shutdown daemon: FIFO trigger for the LSPosed system_server hook plus a
# sys.powerctl fallback. It reads the app-generated restore script, so it works
# even when the Nemu process is not running.
if [ -f "$MODDIR/shutdown_daemon.sh" ]; then
    chmod 755 "$MODDIR/shutdown_daemon.sh" "$MODDIR/restore_if_frozen.sh" 2>/dev/null
    if command -v nohup >/dev/null 2>&1; then
        nohup /system/bin/sh "$MODDIR/shutdown_daemon.sh" >/dev/null 2>&1 &
    else
        /system/bin/sh "$MODDIR/shutdown_daemon.sh" >/dev/null 2>&1 &
    fi
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
