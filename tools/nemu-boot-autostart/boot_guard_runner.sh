#!/system/bin/sh
# NEMU_GUARD_RUNNER_OWNER=nemu_boot_autostart
# Stable service.d entrypoint. The persistent hook must not exec the guard
# directly: a rescue can run multiple pm scans, and without an outer deadline
# a wedged PackageManager could keep the process alive indefinitely. This
# runner applies the same bounded timeout that service.sh uses, choosing the
# larger rescue budget when a rescue flag is present.
GUARD_DIR=${NEMU_GUARD_DIR:-/data/adb/nemu/boot_guard}
GUARD="$GUARD_DIR/boot_guard.sh"
MODE=${1:-service}
BUDGET=${NEMU_GUARD_TIMEOUT:-480}
RESCUE_BUDGET=${NEMU_RESCUE_TIMEOUT:-1200}

if [ "$MODE" = "rescue" ] || [ -f "$GUARD_DIR/rescue.flag" ]; then
    BUDGET=$RESCUE_BUDGET
elif [ "$MODE" = "service" ]; then
    # This boot may cross the rescue threshold inside the service scan; use
    # the rescue budget proactively so the outer deadline does not cut it.
    threshold=${NEMU_RESCUE_THRESHOLD:-3}
    attempts=$(cat "$GUARD_DIR/attempts" 2>/dev/null)
    case "$attempts" in ''|*[!0-9]*) attempts=0 ;; esac
    if [ $((attempts + 1)) -ge "$threshold" ]; then
        BUDGET=$RESCUE_BUDGET
    fi
fi

log_timeout() {
    mkdir -p "$GUARD_DIR" 2>/dev/null
    printf '%s guard runner timeout after %ss (mode=%s)\n' \
        "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$BUDGET" "$MODE" \
        >> "$GUARD_DIR/guard.log" 2>/dev/null
    chmod 600 "$GUARD_DIR/guard.log" 2>/dev/null
    sync 2>/dev/null
}

if [ ! -x "$GUARD" ]; then
    printf '%s guard runner: guard missing (%s)\n' \
        "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$GUARD" \
        >> "$GUARD_DIR/guard.log" 2>/dev/null
    chmod 600 "$GUARD_DIR/guard.log" 2>/dev/null
    exit 0
fi

if command -v timeout >/dev/null 2>&1; then
    timeout "$BUDGET" "$GUARD" "$MODE"
    status=$?
    [ "$status" -eq 124 ] && log_timeout
    exit "$status"
fi

"$GUARD" "$MODE" &
guard_pid=$!
timeout_marker="$GUARD_DIR/.runner_timeout.$$"
rm -f "$timeout_marker" 2>/dev/null
(
    sleep "$BUDGET"
    : > "$timeout_marker" 2>/dev/null
    kill "$guard_pid" 2>/dev/null
) >/dev/null 2>&1 &
timer_pid=$!
wait "$guard_pid"
status=$?
kill "$timer_pid" 2>/dev/null
wait "$timer_pid" 2>/dev/null
if [ -f "$timeout_marker" ]; then
    rm -f "$timeout_marker" 2>/dev/null
    log_timeout
fi
exit "$status"
