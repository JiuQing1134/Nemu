#!/system/bin/sh
# Regression fixtures for the deep-uninstall manifest parser and delay config.
# Run with any POSIX shell:
#   sh tools/nemu-boot-autostart/tests/test_deep_restore_parsers.sh
set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmpdir=$(mktemp -d)
export NEMU_DEEP_DIR="$tmpdir"
export NEMU_DEEP_LIB_ONLY=1
. "$SCRIPT_DIR/../deep_restore.sh"

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

manifest="$tmpdir/manifest.tsv"
{
    printf 'com.example.one\tLabel One\t123\t1.0\t%s/apk/com.example.one/123-1700000000\t1700000000\tbase.apk,split_0.apk\t1\n' "$tmpdir"
    printf 'com.example.two\tLabel Two\t456\t2.0\t%s/apk/com.example.two/456-1700000001\t1700000001\tbase.apk\t0\n' "$tmpdir"
    printf 'com.example.empty\tEmpty\t1\t1.0\t\t1\tbase.apk\t0\n'
    printf 'malformed line\n'
} > "$manifest"

rows=$(deep_manifest_rows "$manifest")
expected=$(printf \
    'com.example.one\t%s/apk/com.example.one/123-1700000000\tbase.apk,split_0.apk\t123\ncom.example.two\t%s/apk/com.example.two/456-1700000001\tbase.apk\t456' \
    "$tmpdir" "$tmpdir")
check_eq "$expected" "$rows" "manifest rows keep package, dir, files and versionCode"

deep_valid_package "com.example.one"
check_status 0 $? "valid package name"
deep_valid_package "bad package"
check_status 1 $? "space in package name is rejected"
deep_valid_package "../evil"
check_status 1 $? "parent traversal in package name is rejected"

deep_valid_file "base.apk"
check_status 0 $? "plain APK file name"
deep_valid_file "../base.apk"
check_status 1 $? "parent traversal in file name is rejected"
deep_valid_file "dir/base.apk"
check_status 1 $? "slash in file name is rejected"

deep_valid_backup_dir "$tmpdir/apk/com.example.one/123-1700000000"
check_status 0 $? "backup dir under the deep-uninstall root"
deep_valid_backup_dir "$tmpdir/other/com.example.one"
check_status 1 $? "backup dir outside the APK root is rejected"

config="$tmpdir/config"
printf 'delay_seconds=5\n' > "$config"
check_eq "30" "$(deep_delay_seconds)" "delay below 30 is clamped"
printf 'delay_seconds=120\n' > "$config"
check_eq "120" "$(deep_delay_seconds)" "valid delay is kept"
printf 'delay_seconds=9999\n' > "$config"
check_eq "600" "$(deep_delay_seconds)" "delay above 600 is clamped"
printf 'delay_seconds=abc\n' > "$config"
check_eq "90" "$(deep_delay_seconds)" "invalid delay falls back to 90"
rm -f "$config"
check_eq "90" "$(deep_delay_seconds)" "missing config uses the default"

rm -rf "$tmpdir"

if [ "$failures" -ne 0 ]; then
    echo "FAILED $failures fixture(s)"
    exit 1
fi
echo "PASS all deep-restore parser fixtures"
