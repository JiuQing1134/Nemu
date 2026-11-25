#!/system/bin/sh
# Nemu deep-uninstall boot bridge.
#
# At boot: restore every package listed in manifest.tsv that is not installed
# for user 0. This gets launchers/SystemUI/OEM services back before the user
# reaches the desktop. After sys.boot_completed plus a stabilisation delay,
# re-uninstall only the packages this boot restored. Packages that were
# already installed at boot (the user restored/kept them) are never touched.
#
# Runs as root from service.sh. Everything is logged under
# /data/adb/nemu/deep_uninstall/boot.log.

NEMU_DIR=${NEMU_DIR:-/data/adb/nemu}
DEEP_DIR=${NEMU_DEEP_DIR:-$NEMU_DIR/deep_uninstall}
MANIFEST=${NEMU_DEEP_MANIFEST:-$DEEP_DIR/manifest.tsv}
STATE=${NEMU_DEEP_STATE:-$DEEP_DIR/restored_this_boot.tsv}
READY=${NEMU_DEEP_READY:-$DEEP_DIR/auto_restore_ready}
LOG=${NEMU_DEEP_LOG:-$DEEP_DIR/boot.log}
CONFIG=${NEMU_DEEP_CONFIG:-$DEEP_DIR/config}
LOCK=${NEMU_DEEP_LOCK:-$DEEP_DIR/.restore.lock}
WAIT_PM_SECONDS=${NEMU_DEEP_WAIT_PM:-120}
WAIT_BOOT_SECONDS=${NEMU_DEEP_WAIT_BOOT:-180}
DEFAULT_DELAY=90
TAB=$(printf '\t')
RESTORE_ONLY=0
[ "${1:-}" = "--restore-only" ] && RESTORE_ONLY=1
# In restore-only mode (module uninstall), never leave a re-uninstall plan.
if [ "$RESTORE_ONLY" = "1" ]; then
    STATE="$DEEP_DIR/.restore_only_state.$$"
fi

log_line() {
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$*" \
        >> "$LOG" 2>&1
    chmod 600 "$LOG" 2>/dev/null
}

boot_id() {
    if [ -n "${NEMU_BOOT_ID:-}" ]; then
        printf '%s\n' "$NEMU_BOOT_ID"
        return
    fi
    cat /proc/sys/kernel/random/boot_id 2>/dev/null || date '+%s'
}

# Print pkg<TAB>dir<TAB>files<TAB>versionCode for valid manifest rows.
deep_manifest_rows() {
    manifest=$1
    [ -f "$manifest" ] || return 0
    awk -F '\t' 'NF >= 8 {
        pkg = $1; dir = $5; files = $7; vc = $3;
        if (pkg == "" || dir == "" || files == "") next;
        print pkg "\t" dir "\t" files "\t" vc;
    }' "$manifest" 2>/dev/null
}

deep_valid_package() {
    case "$1" in
        ''|*[!A-Za-z0-9._]*) return 1 ;;
        *..*) return 1 ;;
    esac
    return 0
}

deep_valid_file() {
    case "$1" in
        ''|*/*|*..*|*\\*) return 1 ;;
    esac
    return 0
}

# The backup root is fixed; a manifest line must not point outside it.
deep_valid_backup_dir() {
    case "$1" in
        "$DEEP_DIR"/apk/*) return 0 ;;
    esac
    return 1
}

deep_delay_seconds() {
    delay=$DEFAULT_DELAY
    if [ -f "$CONFIG" ]; then
        value=$(sed -n 's/^delay_seconds=//p' "$CONFIG" 2>/dev/null | tail -n 1)
        case "$value" in
            ''|*[!0-9]*) ;;
            *) delay=$value ;;
        esac
    fi
    [ "$delay" -lt 30 ] && delay=30
    [ "$delay" -gt 600 ] && delay=600
    printf '%s\n' "$delay"
}

# Sourceable parser tests stop before touching the device.
if [ "${NEMU_DEEP_LIB_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

mkdir -p "$DEEP_DIR" 2>/dev/null
chmod 700 "$DEEP_DIR" 2>/dev/null
: >> "$LOG" 2>/dev/null
chmod 600 "$LOG" 2>/dev/null

# Tell the app that the v1.20 boot bridge is installed. Versioned so an old
# module or a stale marker left after uninstall is not mistaken for v1.20.
printf '1.20\n' > "$READY" 2>/dev/null
chmod 644 "$READY" 2>/dev/null

BOOT_ID=$(boot_id)
DELAY=$(deep_delay_seconds)

# One instance per boot. A stale lock from a previous boot is reclaimed.
if ! mkdir "$LOCK" 2>/dev/null; then
    owner_pid=$(cat "$LOCK/pid" 2>/dev/null)
    owner_boot=$(cat "$LOCK/boot_id" 2>/dev/null)
    if [ "$owner_boot" = "$BOOT_ID" ] &&
        [ -n "$owner_pid" ] && kill -0 "$owner_pid" 2>/dev/null; then
        log_line "deep restore already running pid=$owner_pid; exit"
        exit 0
    fi
    rm -rf "$LOCK" 2>/dev/null
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
printf '%s\n' "$$" > "$LOCK/pid" 2>/dev/null
printf '%s\n' "$BOOT_ID" > "$LOCK/boot_id" 2>/dev/null
chmod 700 "$LOCK" 2>/dev/null
chmod 600 "$LOCK/pid" "$LOCK/boot_id" 2>/dev/null

cleanup() {
    if [ -d "$LOCK" ] && [ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ]; then
        rm -rf "$LOCK" 2>/dev/null
    fi
    [ "$RESTORE_ONLY" = "1" ] && rm -f "$STATE" 2>/dev/null
}
trap cleanup 0
trap 'cleanup; exit 1' 1 2 15

# cmd passes its stdin fd through binder; if stdin is a file under /data/adb
# (adb_data_file), system_server is denied that fd and every pm call fails
# with "Failed transaction (2147483646)". Always feed pm /dev/null.
pm_ready() {
    pm list packages --user 0 < /dev/null 2>/dev/null | grep -q '^package:'
}

is_installed() {
    pm list packages --user 0 "$1" < /dev/null 2>/dev/null |
        grep -Fqx "package:$1"
}

# MagicOS keeps a delapp/preload record after uninstalling such a system
# app; installing a backup over it crashes PackageManager. Only a truly
# absent record may be restored from the APK.
package_record_exists() {
    pm list packages -u --user 0 "$1" < /dev/null 2>/dev/null |
        grep -Fqx "package:$1"
}

installed_version_code() {
    pm list packages --show-versioncode --user 0 "$1" < /dev/null 2>/dev/null |
        awk -v pkg="$1" '
            $1 == "package:" pkg {
                for (i = 1; i <= NF; i++) {
                    if ($i ~ /^versionCode:/) {
                        sub(/^versionCode:/, "", $i)
                        print $i
                        exit
                    }
                }
            }'
}

install_backup() {
    dir=$1
    files=$2
    # PackageInstaller runs in system_server, which is denied reading the
    # adb_data_file backups under /data/adb. Stage copies under
    # /data/local/tmp (shell_data_file) first. MagicOS 10 also lacks the
    # multi-APK pm subcommand; `pm install` accepts one or more paths.
    stage=/data/local/tmp/nemu_deep_install
    rm -rf "$stage" 2>/dev/null
    mkdir -p "$stage" 2>/dev/null || return 1
    set -- pm install -r -d --user 0
    old_ifs=$IFS
    IFS=,
    for file in $files; do
        if ! deep_valid_file "$file"; then
            IFS=$old_ifs
            rm -rf "$stage" 2>/dev/null
            return 1
        fi
        if [ ! -f "$dir/$file" ]; then
            IFS=$old_ifs
            rm -rf "$stage" 2>/dev/null
            return 1
        fi
        # Backups are stored as *.apk.bak so system cleaners ignore them;
        # rename back to the installable APK name under /data/local/tmp.
        install_name=${file%.bak}
        if ! cp -f "$dir/$file" "$stage/$install_name"; then
            IFS=$old_ifs
            rm -rf "$stage" 2>/dev/null
            return 1
        fi
        chmod 644 "$stage/$install_name" 2>/dev/null
        set -- "$@" "$stage/$install_name"
    done
    IFS=$old_ifs
    "$@" < /dev/null >/dev/null 2>&1
    result=$?
    rm -rf "$stage" 2>/dev/null
    return $result
}

log_line "deep restore start boot=$BOOT_ID delay=${DELAY}s"

i=0
while [ "$i" -lt "$WAIT_PM_SECONDS" ]; do
    if pm_ready; then break; fi
    sleep 1
    i=$((i + 1))
done
if ! pm_ready; then
    log_line "PackageManager not ready after ${WAIT_PM_SECONDS}s; exit"
    exit 1
fi
log_line "PackageManager ready after ${i}s"

if [ "$RESTORE_ONLY" = "0" ]; then
    # This boot's manifest and the last manifest that reached boot_completed.
    # The rescue pass uses the latter to roll back only newly added packages.
    cp -f "$MANIFEST" "$DEEP_DIR/manifest.boot.tsv" 2>/dev/null
    chmod 600 "$DEEP_DIR/manifest.boot.tsv" 2>/dev/null
fi

# If another instance already restored this boot, reuse its state.
state_boot=$(head -n 1 "$STATE" 2>/dev/null | cut -f1)
restore_needed=1
[ "$state_boot" = "$BOOT_ID" ] && restore_needed=0

if [ "$restore_needed" -eq 1 ]; then
    state_tmp="$STATE.tmp.$$"
    : > "$state_tmp" 2>/dev/null
    rows="$DEEP_DIR/.manifest_rows.$$"
    deep_manifest_rows "$MANIFEST" > "$rows" 2>/dev/null
    while IFS="$TAB" read -r pkg dir files vc; do
        [ -n "$pkg" ] || continue
        if ! deep_valid_package "$pkg"; then
            log_line "skip invalid package: $pkg"
            continue
        fi
        if ! deep_valid_backup_dir "$dir"; then
            log_line "skip $pkg: invalid backup dir $dir"
            continue
        fi
        if is_installed "$pkg"; then
            log_line "skip $pkg: already installed"
            continue
        fi
        install_existing_out=$(pm install-existing --user 0 "$pkg" < /dev/null 2>&1)
        install_existing_rc=$?
        install_existing_ok=0
        [ "$install_existing_rc" -eq 0 ] && install_existing_ok=1
        if [ "$install_existing_ok" -eq 0 ]; then
            case "$install_existing_out" in
                *NameNotFoundException*|*"doesn't exist"*) ;;
                *)
                    # Transient service failure: retry once before touching
                    # the backup. A backup install over a live package
                    # crashed MagicOS 10's PackageManager.
                    sleep 1
                    install_existing_out=$(pm install-existing --user 0 "$pkg" < /dev/null 2>&1)
                    [ "$?" -eq 0 ] && install_existing_ok=1
                    ;;
            esac
        fi
        if [ "$install_existing_ok" -eq 1 ]; then
            # Success is authoritative; poll only to see it in pm list.
            wait_i=0
            while [ "$wait_i" -lt 15 ]; do
                is_installed "$pkg" && break
                sleep 1
                wait_i=$((wait_i + 1))
            done
        else
            if package_record_exists "$pkg"; then
                log_line "skip backup install for $pkg: system record exists (needs reboot)"
            else
                install_backup "$dir" "$files" || true
            fi
        fi
        if is_installed "$pkg"; then
            pm enable --user 0 "$pkg" < /dev/null >/dev/null 2>&1 || true
            actual_vc=$(installed_version_code "$pkg")
            printf '%s\t%s\t%s\n' \
                "$BOOT_ID" "$pkg" "${actual_vc:-$vc}" >> "$state_tmp"
            log_line "restored $pkg version=${actual_vc:-$vc}"
        elif [ "$install_existing_ok" -eq 1 ]; then
            # Accepted by PackageManager but not visible yet. Never install
            # the backup in this window; record it so the re-uninstall pass
            # handles it once it appears.
            printf '%s\t%s\t%s\n' "$BOOT_ID" "$pkg" "$vc" >> "$state_tmp"
            log_line "install-existing accepted $pkg; not visible yet, skip backup install"
        else
            log_line "restore failed $pkg (install-existing: $install_existing_out)"
        fi
    done < "$rows"
    rm -f "$rows" 2>/dev/null
    if [ -s "$state_tmp" ]; then
        mv -f "$state_tmp" "$STATE" 2>/dev/null ||
            cp -f "$state_tmp" "$STATE" 2>/dev/null
    else
        : > "$STATE" 2>/dev/null
    fi
    rm -f "$state_tmp" 2>/dev/null
    chmod 600 "$STATE" 2>/dev/null
    log_line "restore pass done; pending=$(wc -l < "$STATE" 2>/dev/null | tr -d ' ')"
else
    log_line "restore already recorded for this boot; reuse state"
fi

if [ "$RESTORE_ONLY" = "1" ]; then
    log_line "restore-only done; no re-uninstall"
    exit 0
fi

# Wait for a completed boot, then let the system settle before re-uninstalling.
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] &&
    [ "$i" -lt "$WAIT_BOOT_SECONDS" ]; do
    sleep 2
    i=$((i + 2))
done
if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
    log_line "boot_completed not seen after ${WAIT_BOOT_SECONDS}s; keep restored apps"
    exit 0
fi
if [ -f "$NEMU_DIR/boot_guard/rescue.flag" ]; then
    # A rescue boot is not a "last good" baseline: do not promote this
    # manifest, and keep the restored apps installed.
    log_line "boot guard rescue flag present; keep restored apps (no re-uninstall)"
    exit 0
fi
# The boot snapshot is the last manifest known to boot; the rescue pass uses
# it as the rollback baseline for newly added deep-uninstall entries. Fall
# back to the live manifest if the snapshot could not be written.
if [ -f "$DEEP_DIR/manifest.boot.tsv" ]; then
    cp -f "$DEEP_DIR/manifest.boot.tsv" "$DEEP_DIR/manifest.last.tsv" 2>/dev/null
else
    cp -f "$MANIFEST" "$DEEP_DIR/manifest.last.tsv" 2>/dev/null
fi
chmod 600 "$DEEP_DIR/manifest.last.tsv" 2>/dev/null
log_line "boot_completed after ${i}s; waiting ${DELAY}s before re-uninstall"
sleep "$DELAY"

if [ "$(boot_id)" != "$BOOT_ID" ]; then
    log_line "boot id changed; skip re-uninstall"
    exit 0
fi
if [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; then
    log_line "boot_completed lost; skip re-uninstall"
    exit 0
fi

removed=0
failed=0
if [ -f "$STATE" ]; then
    while IFS="$TAB" read -r line_boot pkg expected_vc; do
        [ "$line_boot" = "$BOOT_ID" ] || continue
        deep_valid_package "$pkg" || continue
        if ! is_installed "$pkg"; then
            log_line "skip re-uninstall $pkg: not installed"
            continue
        fi
        actual_vc=$(installed_version_code "$pkg")
        if [ -n "$expected_vc" ] && [ -n "$actual_vc" ] &&
            [ "$actual_vc" != "$expected_vc" ]; then
            log_line "skip re-uninstall $pkg: version $expected_vc -> $actual_vc"
            continue
        fi
        pm uninstall -k --user 0 "$pkg" < /dev/null >/dev/null 2>&1
        if is_installed "$pkg"; then
            failed=$((failed + 1))
            log_line "re-uninstall failed $pkg"
        else
            removed=$((removed + 1))
            log_line "re-uninstalled $pkg"
        fi
    done < "$STATE"
fi
rm -f "$STATE" 2>/dev/null
log_line "deep restore done; re-uninstalled=$removed failed=$failed"

# Bound the log without deleting the start of this boot's pass.
LOG_TMP="$LOG.tmp.$$"
tail -n 300 "$LOG" > "$LOG_TMP" 2>/dev/null &&
    mv -f "$LOG_TMP" "$LOG" 2>/dev/null
rm -f "$LOG_TMP" 2>/dev/null
chmod 600 "$LOG" 2>/dev/null
sync 2>/dev/null
exit 0
