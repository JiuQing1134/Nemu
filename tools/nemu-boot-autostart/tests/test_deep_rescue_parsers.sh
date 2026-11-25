#!/system/bin/sh
# Regression fixtures for the deep-uninstall rescue parsers, the last-good
# manifest rollback, and the module-uninstall restore-only wiring.
#
# No device path is touched: deep_rescue.sh returns after its pure functions
# when NEMU_DEEP_RESCUE_LIB_ONLY=1.
#
# Run with any POSIX shell:
#   sh tools/nemu-boot-autostart/tests/test_deep_rescue_parsers.sh
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmpdir=$(mktemp -d)
cleanup() { rm -rf "$tmpdir"; }
trap cleanup 0

export NEMU_DEEP_DIR="$tmpdir/deep_uninstall"
export NEMU_GUARD_DIR="$tmpdir/guard"
export NEMU_DEEP_RESCUE_LIB_ONLY=1
mkdir -p "$NEMU_DEEP_DIR" "$NEMU_GUARD_DIR"
. "$SCRIPT_DIR/../deep_rescue.sh"

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

check_contains() {
    needle=$1
    file=$2
    label=$3
    if grep -Fq -- "$needle" "$file" 2>/dev/null; then
        echo "PASS $label"
    else
        echo "FAIL $label: [$needle] not found in $file"
        failures=$((failures + 1))
    fi
}

check_line_order() {
    first=$1
    second=$2
    file=$3
    label=$4
    if [ -n "$first" ] && [ -n "$second" ] && [ "$first" -lt "$second" ]; then
        echo "PASS $label"
    else
        echo "FAIL $label: first line [$first] second line [$second]"
        failures=$((failures + 1))
    fi
}

# --- package-name validation ------------------------------------------------

deep_rescue_valid_package "com.example.one"
check_status 0 $? "valid package name"
deep_rescue_valid_package "bad package"
check_status 1 $? "space in package name is rejected"
deep_rescue_valid_package "../evil"
check_status 1 $? "parent traversal in package name is rejected"
deep_rescue_valid_package "malformed"
check_status 1 $? "name without a dot is rejected"
deep_rescue_valid_package "com..example"
check_status 1 $? "empty segment is rejected"

# --- rescue trigger / previous-boot health ---------------------------------

check_eq "2" "$RESCUE_AFTER" "default rescue threshold waits for two failed boots"

deep_rescue_should_run "0" "$tmpdir/no.flag" "1"
check_status 1 $? "zero attempts and no flag do not rescue"
deep_rescue_should_run "1" "$tmpdir/no.flag" "1"
check_status 0 $? "one previous failure triggers at threshold 1"
: > "$tmpdir/rescue.flag"
deep_rescue_should_run "0" "$tmpdir/rescue.flag" "1"
check_status 0 $? "guard rescue flag forces the rescue"

guard="$NEMU_GUARD_DIR"
printf 'boot-old\n' > "$guard/last_boot_id"
deep_rescue_previous_boot_healthy
check_status 1 $? "no marker is not healthy"
printf 'boot-old 1700000000\n' > "$guard/boot_completed"
deep_rescue_previous_boot_healthy
check_status 0 $? "boot_completed of the last boot is healthy"
rm -f "$guard/boot_completed"
printf 'boot-other 1700000000\n' > "$guard/boot_completed"
deep_rescue_previous_boot_healthy
check_status 1 $? "boot_completed of an older boot is not healthy"
rm -f "$guard/boot_completed"
printf 'boot-old 1700000000\n' > "$guard/planned_shutdown"
deep_rescue_previous_boot_healthy
check_status 0 $? "planned shutdown of the last boot is healthy"
rm -f "$guard/planned_shutdown"
printf 'boot-old\n' > "$guard/clean_shutdown"
deep_rescue_previous_boot_healthy
check_status 0 $? "clean shutdown of the last boot is healthy"

# --- last-good vs new-package filtering -------------------------------------

manifest="$NEMU_DEEP_DIR/manifest.tsv"
last_good="$NEMU_DEEP_DIR/manifest.last.tsv"
{
    printf 'com.example.old.one\tOne\t1\t1.0\t%s/apk/old.one\t1\tbase.apk\t1\n' "$tmpdir"
    printf 'com.example.new.one\tNew One\t2\t1.0\t%s/apk/new.one\t2\tbase.apk\t1\n' "$tmpdir"
    printf 'com.example.old.two\tOld Two\t3\t1.0\t%s/apk/old.two\t3\tbase.apk\t0\n' "$tmpdir"
    printf 'com.example.new.two\tNew Two\t4\t1.0\t%s/apk/new.two\t4\tbase.apk\t0\n' "$tmpdir"
    printf 'com.example.old.one\tOne\t1\t1.0\tduplicate-row\t1\tbase.apk\t1\n'
    printf 'malformed line without a package name\n'
} > "$manifest"
printf 'com.example.old.one\ncom.example.old.two\n' > "$last_good"

expected=$(printf 'com.example.new.one\ncom.example.new.two')
check_eq "$expected" "$(deep_rescue_new_packages "$manifest" "$last_good")" \
    "only packages absent from manifest.last.tsv are rescued"

expected=$(printf \
    'com.example.old.one\ncom.example.new.one\ncom.example.old.two\ncom.example.new.two')
check_eq "$expected" \
    "$(deep_rescue_new_packages "$manifest" "$tmpdir/missing.last.tsv")" \
    "missing last-good baseline rescues every unique valid package"

expected=$(
    printf 'com.example.old.one\tOne\t1\t1.0\t%s/apk/old.one\t1\tbase.apk\t1\n' "$tmpdir"
    printf 'com.example.old.two\tOld Two\t3\t1.0\t%s/apk/old.two\t3\tbase.apk\t0\n' "$tmpdir"
    printf 'com.example.old.one\tOne\t1\t1.0\tduplicate-row\t1\tbase.apk\t1\n'
    printf 'malformed line without a package name\n'
)
check_eq "$expected" \
    "$(deep_rescue_filter_manifest "$manifest" \
        'com.example.new.one com.example.new.two')" \
    "manifest filter drops only rescued packages and keeps other lines"

# A malformed single-token row that exactly matches a target is removed too.
printf 'com.example.new.one\nextra\tfield\n' > "$tmpdir/tiny.tsv"
check_eq "extra	field" \
    "$(deep_rescue_filter_manifest "$tmpdir/tiny.tsv" 'com.example.new.one')" \
    "manifest filter handles rows without the full field count"

# --- package-restrictions inst="false" stripping ----------------------------

xml="$tmpdir/package-restrictions.xml"
{
    printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
    printf '%s\n' '<package-restrictions>'
    printf '  <pkg name="com.example.one" inst="false" enabled="0" />\n'
    printf '  <pkg name="com.example.two" inst="false" enabled="0" />\n'
    printf '  <pkg name="com.example.three" inst="false" />\n'
    printf '  <other name="com.example.one" inst="false" />\n'
    printf '%s\n' '</package-restrictions>'
} > "$xml"
expected=$(
    printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
    printf '%s\n' '<package-restrictions>'
    printf '  <pkg name="com.example.one" enabled="0" />\n'
    printf '  <pkg name="com.example.two" inst="false" enabled="0" />\n'
    printf '  <pkg name="com.example.three" />\n'
    printf '  <other name="com.example.one" inst="false" />\n'
    printf '%s\n' '</package-restrictions>'
)
check_eq "$expected" \
    "$(deep_rescue_strip_inst "$xml" 'com.example.one com.example.three')" \
    "inst=false is stripped only from targeted <pkg> lines"

check_eq "$(cat "$xml")" \
    "$(deep_rescue_strip_inst "$xml" 'com.example.absent')" \
    "non-target drop list leaves the XML byte-for-byte unchanged"

# --- rescue trigger ---------------------------------------------------------

flag="$tmpdir/rescue.flag"
rm -f "$flag" 2>/dev/null
deep_rescue_should_run "0" "$flag" "1"
check_status 1 $? "fresh attempt counter does not rescue"
deep_rescue_should_run "1" "$flag" "1"
check_status 0 $? "attempts>=1 rescues on the second boot"
deep_rescue_should_run "2" "$flag" "1"
check_status 0 $? "attempts above the threshold rescues"
deep_rescue_should_run "" "$flag" "1"
check_status 1 $? "missing attempts file does not rescue"
deep_rescue_should_run "garbage" "$flag" "1"
check_status 1 $? "invalid attempts value does not rescue"
deep_rescue_should_run "1" "$flag" "2"
check_status 1 $? "attempts below a custom threshold does not rescue"
deep_rescue_should_run "2" "$flag" "2"
check_status 0 $? "attempts at a custom threshold rescues"
deep_rescue_should_run "1" "$flag" ""
check_status 0 $? "empty threshold falls back to 1"
: > "$flag"
deep_rescue_should_run "0" "$flag" "5"
check_status 0 $? "guard rescue flag forces rescue"
rm -f "$flag"

# --- uninstall / restore-only wiring ---------------------------------------

deep_restore="$SCRIPT_DIR/../deep_restore.sh"
uninstall="$SCRIPT_DIR/../uninstall.sh"
post_fs_data="$SCRIPT_DIR/../post-fs-data.sh"

check_contains 'pm uninstall -k --user 0 "$pkg"' "$deep_restore" \
    "deep re-uninstall keeps package data"
bare=$(grep -n 'pm uninstall --user' "$SCRIPT_DIR"/../*.sh 2>/dev/null || true)
check_eq "" "$bare" "no bare pm uninstall --user in any module script"
check_contains 'pm enable --user 0 "$pkg"' "$deep_restore" \
    "restored packages are enabled"

check_contains '--restore-only' "$deep_restore" \
    "deep_restore supports restore-only mode"
check_contains 'restore-only done; no re-uninstall' "$deep_restore" \
    "restore-only exits before the re-uninstall pass"
check_contains 'deep_restore.sh' "$uninstall" \
    "uninstall invokes the deep restore script"
check_contains '--restore-only' "$uninstall" \
    "uninstall uses the restore-only mode"
check_contains 'restored_this_boot.tsv' "$uninstall" \
    "uninstall clears the deep-uninstall pending state"

restore_line=$(grep -Fn -- '"$restore_script" --restore-only' "$uninstall" \
    2>/dev/null | head -n 1 | cut -d: -f1)
marker_line=$(grep -Fn 'auto_restore_ready' "$uninstall" 2>/dev/null |
    head -n 1 | cut -d: -f1)
check_line_order "$restore_line" "$marker_line" "$uninstall" \
    "uninstall restores packages before removing the capability marker"

rescue_line=$(grep -Fn 'deep_rescue.sh' "$post_fs_data" 2>/dev/null |
    head -n 1 | cut -d: -f1)
guard_line=$(grep -Fn 'boot_guard.sh" post-fs-data' "$post_fs_data" \
    2>/dev/null | head -n 1 | cut -d: -f1)
check_line_order "$rescue_line" "$guard_line" "$post_fs_data" \
    "post-fs-data runs deep rescue before the boot guard"

if [ "$failures" -ne 0 ]; then
    echo "FAILED $failures fixture(s)"
    exit 1
fi
echo "PASS all deep-rescue parser and wiring fixtures"
