#!/system/bin/sh
# Regression fixtures for the guard's dumpsys/appops parsers.
# Run with any POSIX shell: sh tools/nemu-boot-autostart/tests/test_restore_parsers.sh
# The parser code is sourced from boot_guard.sh in library-only mode.
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
NEMU_GUARD_LIB_ONLY=1
export NEMU_GUARD_LIB_ONLY
. "$SCRIPT_DIR/../boot_guard.sh"

failures=0
check_eq() {
    expected=$1
    actual=$2
    label=$3
    if [ "$expected" = "$actual" ]; then
        echo "PASS $label"
    else
        echo "FAIL $label: expected=[$expected] actual=[$actual]"
        failures=$((failures + 1))
    fi
}

check_status() {
    expected=$1
    actual=$2
    label=$3
    if [ "$expected" -eq "$actual" ]; then
        echo "PASS $label"
    else
        echo "FAIL $label: expected status $expected actual $actual"
        failures=$((failures + 1))
    fi
}

# AOSP prints bare class names, not pkg/Class. This is the format captured from
# docs/audit-2026-11-22/state-before-reboot-20261122.txt.
bare_dump='    User 0: ceDataInode=1 installed=true enabled=1
      disabledComponents:
        com.hihonor.ncdft.NcDftService
        com.example.app.OwnedReceiver
      other: value'
printf '%s\n' "$bare_dump" |
    component_still_disabled "com.example.app/com.example.app.OwnedReceiver"
check_status 0 $? "bare class is still disabled"

printf '%s\n' "$bare_dump" |
    component_still_disabled "com.example.app/com.example.app.EnabledService"
check_status 1 $? "absent component is restored"

slash_dump='    User 0: enabled=1
      disabledComponents:
        com.example.app/.OwnedReceiver
'
printf '%s\n' "$slash_dump" |
    component_still_disabled "com.example.app/com.example.app.OwnedReceiver"
check_status 0 $? "pkg/.Class form matches"

restored_dump='    User 0: enabled=1
      disabledComponents:
        com.example.app.OtherService
'
printf '%s\n' "$restored_dump" |
    component_still_disabled "com.example.app/com.example.app.OwnedReceiver"
check_status 1 $? "other component does not match"

user10_dump='    User 0: enabled=1
    User 10: enabled=1
      disabledComponents:
        com.example.app.OwnedReceiver
'
printf '%s\n' "$user10_dump" |
    component_still_disabled "com.example.app/com.example.app.OwnedReceiver"
check_status 1 $? "User 10 disabled state does not affect User 0"

truncated_dump='    User 0: enabled=1'
printf '%s\n' "$truncated_dump" | component_dump_complete
check_status 1 $? "dump ending at User 0 is incomplete"
printf '%s\n' "$bare_dump" | component_dump_complete
check_status 0 $? "dump with a component section is complete"

# AppOpsService appends ; time=..., ; rejectTime=..., ; duration=..., (running),
# and prints UID-level lines first with a "Uid mode:" prefix.
check_eq "allow" \
    "$(printf '%s\n' 'RUN_ANY_IN_BACKGROUND: allow; time=+1h2m3s ago' | appops_mode_from_output)" \
    "AppOps suffix does not leak into the mode"
check_eq "allow" \
    "$(printf '%s\n' 'Uid mode: RUN_ANY_IN_BACKGROUND: ignore' 'RUN_ANY_IN_BACKGROUND: allow; rejectTime=+1s ago' | appops_mode_from_output)" \
    "UID-mode line is skipped"
check_eq "ignore" \
    "$(printf '%s\n' 'RUN_ANY_IN_BACKGROUND: mode=ignore (running)' | appops_mode_from_output)" \
    "mode= prefix and (running) are stripped"

# Standby bucket must be compared as an integer token, not a substring.
check_eq "10" \
    "$(printf '%s\n' '10' | standby_bucket_from_output)" \
    "plain standby bucket"
check_eq "45" \
    "$(printf '%s\n' 'Restricted bucket: 45' | standby_bucket_from_output)" \
    "labelled standby bucket"

# A guard-missed boot must not lose the one unconsumed planned marker: the
# daemon keeps it in planned_shutdown.prev and count_attempt consumes either
# slot for the previous counted boot.
counter_dir=$(mktemp -d)
counter_attempts=$(
    export NEMU_GUARD_DIR="$counter_dir"
    export NEMU_BOOT_ID=boot-new
    export NEMU_RESCUE_THRESHOLD=99
    export NEMU_GUARD_LIB_ONLY=1
    . "$SCRIPT_DIR/../boot_guard.sh"
    printf 'boot-old\n' > "$GUARD_DIR/last_boot_id"
    printf 'boot-skipped 1700000000\n' > "$GUARD_DIR/planned_shutdown"
    printf 'boot-old 1600000000\n' > "$GUARD_DIR/planned_shutdown.prev"
    count_attempt
    cat "$GUARD_DIR/attempts"
)
check_eq "1" "$counter_attempts" "prev planned marker resets the counter"
check_eq \
    "boot-new planned boot-old 1600000000" \
    "$(cat "$counter_dir/last_shutdown")" \
    "last_shutdown records the prev planned epoch"
rm -rf "$counter_dir"

if [ "$failures" -ne 0 ]; then
    echo "FAILED $failures fixture(s)"
    exit 1
fi
echo "PASS all restore parser fixtures"
