#!/system/bin/sh
# Nemu deep-uninstall last-resort rescue.
#
# Runs from post-fs-data.sh, before system_server starts and before
# boot_guard.sh can disable this module. If the previous boot did not reach the
# guard's success path, packages that the app added to the deep-uninstall
# manifest since the last good boot are "rescued": their inst="false" entries
# are removed from the user's package-restrictions copies so PackageManager
# considers them installed again, and they are removed from the manifest so the
# app does not deep-uninstall them again after this boot.
#
# Only packages listed in manifest.tsv (field 1) are ever touched. The manifest
# is filtered down to the last-good baseline (manifest.last.tsv); when that
# baseline is missing every current manifest package counts as new.
#
# Fail-soft: each restrictions file is converted and edited in sibling
# temporary files. The original is replaced only by an atomic rename after
# owner, mode and SELinux context have been restored. Missing tools, failed
# conversions and failed renames leave the original file untouched.

NEMU_DIR=${NEMU_DIR:-/data/adb/nemu}
DEEP_DIR=${NEMU_DEEP_DIR:-$NEMU_DIR/deep_uninstall}
MANIFEST=${NEMU_DEEP_MANIFEST:-$DEEP_DIR/manifest.tsv}
LAST_GOOD=${NEMU_DEEP_LAST_GOOD:-$DEEP_DIR/manifest.last.tsv}
GUARD_DIR=${NEMU_GUARD_DIR:-$NEMU_DIR/boot_guard}
ATTEMPTS_FILE=${NEMU_GUARD_ATTEMPTS:-$GUARD_DIR/attempts}
BOOT_ID_FILE=${NEMU_GUARD_BOOT_ID:-$GUARD_DIR/last_boot_id}
RESCUE_FLAG=${NEMU_GUARD_RESCUE_FLAG:-$GUARD_DIR/rescue.flag}
RESCUE_AFTER=${NEMU_DEEP_RESCUE_AFTER:-2}
RESTRICTIONS_DIR=${NEMU_RESTRICTIONS_DIR:-/data/system/users/0}
LOG=${NEMU_DEEP_RESCUE_LOG:-$DEEP_DIR/rescue.log}
TAB=$(printf '\t')
RESCUE_CHANGED=0
PRIMARY_OK=0

deep_rescue_boot_id() {
    if [ -n "${NEMU_BOOT_ID:-}" ]; then
        printf '%s\n' "$NEMU_BOOT_ID"
        return
    fi
    cat /proc/sys/kernel/random/boot_id 2>/dev/null || date '+%s'
}

# Android package names are dot-separated Java identifiers. Reject anything
# else so a corrupt manifest line cannot reach the XML editor as a pattern.
deep_rescue_valid_package() {
    case "$1" in
        ''|*[!A-Za-z0-9._]*) return 1 ;;
        .*|*.|*..*) return 1 ;;
        *.*) return 0 ;;
    esac
    return 1
}

# Run only after a previous boot failed. attempts is read before
# boot_guard.sh counts/resets it for this boot; a guard rescue flag always
# triggers. An invalid or empty threshold falls back to 1.
deep_rescue_should_run() {
    attempts=$1
    flag=$2
    threshold=$3
    case "$threshold" in
        ''|*[!0-9]*) threshold=1 ;;
    esac
    [ -f "$flag" ] && return 0
    case "$attempts" in
        ''|*[!0-9]*) attempts=0 ;;
    esac
    [ "$attempts" -ge "$threshold" ]
}

# attempts is read before boot_guard.sh resets it for this boot. If the last
# boot reached a healthy marker (boot_completed, clean or planned shutdown),
# this boot must not run the rescue just because the counter was still stale.
deep_rescue_previous_boot_healthy() {
    last=$(cat "$BOOT_ID_FILE" 2>/dev/null)
    [ -n "$last" ] || return 1
    for marker in boot_completed planned_shutdown planned_shutdown.prev; do
        boot=$(cut -d' ' -f1 "$GUARD_DIR/$marker" 2>/dev/null)
        [ "$boot" = "$last" ] && return 0
    done
    for marker in clean_shutdown clean_shutdown.prev; do
        [ "$(cat "$GUARD_DIR/$marker" 2>/dev/null)" = "$last" ] && return 0
    done
    return 1
}

# Package names present in the current manifest but not in the last-good one.
# A missing baseline makes every current package new. Order and duplicates of
# the current manifest are otherwise preserved (duplicates are printed once).
deep_rescue_new_packages() {
    current=$1
    last_good=$2
    [ -f "$current" ] || return 0
    [ -f "$last_good" ] || last_good=
    awk -F "$TAB" -v last="$last_good" '
        BEGIN {
            if (last != "") {
                while ((getline line < last) > 0) {
                    split(line, f, "\t")
                    if (f[1] != "") old[f[1]] = 1
                }
                close(last)
            }
        }
        {
            pkg = $1
            if (pkg !~ /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/) next
            if ((pkg in old) || (pkg in seen)) next
            seen[pkg] = 1
            print pkg
        }
    ' "$current" 2>/dev/null
}

# Print the manifest without the lines whose field 1 is in the space-separated
# drop list. Untouched lines are emitted byte-for-byte.
deep_rescue_filter_manifest() {
    manifest=$1
    drop=$2
    [ -f "$manifest" ] || return 0
    awk -F "$TAB" -v drop="$drop" '
        BEGIN {
            n = split(drop, a, " ")
            for (i = 1; i <= n; i++) if (a[i] != "") d[a[i]] = 1
        }
        !($1 in d) { print }
    ' "$manifest" 2>/dev/null
}

# Remove one " inst=\"false\"" attribute from <pkg> lines whose name attribute
# is in the drop list. Other packages and other elements are left untouched.
deep_rescue_strip_inst() {
    text=$1
    drop=$2
    [ -f "$text" ] || return 0
    awk -v drop="$drop" '
        BEGIN {
            n = split(drop, a, " ")
            for (i = 1; i <= n; i++) if (a[i] != "") d[a[i]] = 1
        }
        {
            line = $0
            if (line ~ /<pkg / && match(line, /name="[^"]*"/)) {
                name = substr(line, RSTART + 6, RLENGTH - 7)
                if (name in d) sub(/ inst="false"/, "", line)
            }
            print line
        }
    ' "$text" 2>/dev/null
}

# Sourceable parser tests stop before touching the device.
if [ "${NEMU_DEEP_RESCUE_LIB_ONLY:-0}" = "1" ]; then
    return 0 2>/dev/null || exit 0
fi

log_line() {
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S' 2>/dev/null)" "$*" \
        >> "$LOG" 2>&1
    chmod 600 "$LOG" 2>/dev/null
}

run_bounded() {
    seconds=$1
    shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$seconds" "$@"
    else
        "$@"
    fi
}

# Repair one package-restrictions copy. Returns 0 when the file is absent,
# unchanged or replaced; returns 1 when the original was left untouched.
repair_restrictions_file() {
    orig=$1
    [ -f "$orig" ] || return 0
    if [ -L "$orig" ]; then
        log_line "rescue: skip symlink $orig"
        return 1
    fi
    work="$orig.nemu-rescue.txt.$$"
    fixed="$orig.nemu-rescue.fixed.$$"
    new="$orig.nemu-rescue.new.$$"
    rm -f "$work" "$fixed" "$new" 2>/dev/null

    is_abx=0
    if [ "$(head -c 3 "$orig" 2>/dev/null)" = "ABX" ]; then
        is_abx=1
        if [ ! -x "$ABX2XML" ]; then
            log_line "rescue: skip $orig (abx2xml not available)"
            return 1
        fi
        if ! run_bounded 20 "$ABX2XML" "$orig" "$work" 2>/dev/null; then
            log_line "rescue: skip $orig (abx2xml failed)"
            rm -f "$work" "$fixed" "$new" 2>/dev/null
            return 1
        fi
    else
        if ! cp -f "$orig" "$work" 2>/dev/null; then
            log_line "rescue: skip $orig (copy failed)"
            rm -f "$work" "$fixed" "$new" 2>/dev/null
            return 1
        fi
    fi

    if ! deep_rescue_strip_inst "$work" "$targets" > "$fixed" 2>/dev/null; then
        log_line "rescue: skip $orig (edit failed)"
        rm -f "$work" "$fixed" "$new" 2>/dev/null
        return 1
    fi
    if cmp -s "$work" "$fixed" 2>/dev/null; then
        log_line "rescue: no inst=false entry for targets in $orig"
        rm -f "$work" "$fixed" "$new" 2>/dev/null
        return 0
    fi

    if [ "$is_abx" = "1" ]; then
        if [ ! -x "$XML2ABX" ] ||
            ! run_bounded 20 "$XML2ABX" "$fixed" "$new" 2>/dev/null; then
            log_line "rescue: skip $orig (xml2abx failed)"
            rm -f "$work" "$fixed" "$new" 2>/dev/null
            return 1
        fi
    else
        if ! cp -f "$fixed" "$new" 2>/dev/null; then
            log_line "rescue: skip $orig (copy back failed)"
            rm -f "$work" "$fixed" "$new" 2>/dev/null
            return 1
        fi
    fi

    owner=$(stat -c '%u:%g' "$orig" 2>/dev/null)
    mode=$(stat -c '%a' "$orig" 2>/dev/null)
    case "$owner" in
        ''|*[!0-9:]*) owner= ;;
    esac
    case "$mode" in
        ''|*[!0-7]*) mode= ;;
    esac
    if [ -z "$owner" ] || [ -z "$mode" ] || [ ! -s "$new" ]; then
        log_line "rescue: skip $orig (attributes unreadable or empty output)"
        rm -f "$work" "$fixed" "$new" 2>/dev/null
        return 1
    fi
    if ! chown "$owner" "$new" 2>/dev/null ||
        ! chmod "$mode" "$new" 2>/dev/null; then
        log_line "rescue: skip $orig (chown/chmod failed)"
        rm -f "$work" "$fixed" "$new" 2>/dev/null
        return 1
    fi

    # chcon --reference is the exact-context path; stat %C and restorecon are
    # fallbacks for toybox builds without --reference.
    ctx_ok=0
    if [ -x "$CHCON" ] && "$CHCON" --reference="$orig" "$new" 2>/dev/null; then
        ctx_ok=1
    fi
    if [ "$ctx_ok" -eq 0 ]; then
        ctx=$(stat -c '%C' "$orig" 2>/dev/null)
        case "$ctx" in
            *:*)
                [ -x "$CHCON" ] && "$CHCON" "$ctx" "$new" 2>/dev/null &&
                    ctx_ok=1
                ;;
        esac
    fi
    if [ "$ctx_ok" -eq 0 ] && command -v restorecon >/dev/null 2>&1; then
        restorecon "$new" 2>/dev/null && ctx_ok=1
    fi
    if [ "$ctx_ok" -eq 0 ]; then
        log_line "rescue: skip $orig (SELinux context not preserved)"
        rm -f "$work" "$fixed" "$new" 2>/dev/null
        return 1
    fi

    if mv -f "$new" "$orig" 2>/dev/null; then
        log_line "rescue: repaired $orig"
        RESCUE_CHANGED=1
        rm -f "$work" "$fixed" 2>/dev/null
        return 0
    fi
    log_line "rescue: skip $orig (atomic replace failed)"
    rm -f "$work" "$fixed" "$new" 2>/dev/null
    return 1
}

# Drop the rescued packages from the manifest, keeping a timestamped backup.
# The write is atomic and 0600; a failed backup leaves the manifest untouched.
rollback_manifest() {
    drop=$1
    [ -f "$MANIFEST" ] || return 0
    backup="$MANIFEST.rescue.$BOOT_ID"
    if ! cp -f "$MANIFEST" "$backup" 2>/dev/null; then
        log_line "rescue: manifest backup failed; leave manifest untouched"
        return 1
    fi
    chmod 600 "$backup" 2>/dev/null
    tmp="$MANIFEST.rescue.tmp.$$"
    if ! deep_rescue_filter_manifest "$MANIFEST" "$drop" > "$tmp" 2>/dev/null
    then
        log_line "rescue: manifest filter failed; leave manifest untouched"
        rm -f "$tmp" 2>/dev/null
        return 1
    fi
    chmod 600 "$tmp" 2>/dev/null
    if cmp -s "$tmp" "$MANIFEST" 2>/dev/null; then
        log_line "rescue: manifest already excludes rescued packages"
        rm -f "$tmp" 2>/dev/null
        return 0
    fi
    if ! mv -f "$tmp" "$MANIFEST" 2>/dev/null; then
        log_line "rescue: manifest replace failed; leave manifest untouched"
        rm -f "$tmp" 2>/dev/null
        return 1
    fi
    chmod 600 "$MANIFEST" 2>/dev/null
    log_line "rescue: removed from manifest: $drop (backup $backup)"
    return 0
}

mkdir -p "$DEEP_DIR" 2>/dev/null
chmod 700 "$DEEP_DIR" 2>/dev/null

case "$RESCUE_AFTER" in
    ''|*[!0-9]*) RESCUE_AFTER=1 ;;
esac
BOOT_ID=$(deep_rescue_boot_id)
attempts=$(cat "$ATTEMPTS_FILE" 2>/dev/null)
if ! deep_rescue_should_run "$attempts" "$RESCUE_FLAG" "$RESCUE_AFTER"; then
    exit 0
fi
if deep_rescue_previous_boot_healthy; then
    log_line "rescue: previous boot reached a healthy marker; skip"
    exit 0
fi
flag_state=no
[ -f "$RESCUE_FLAG" ] && flag_state=yes
log_line "deep rescue start boot=$BOOT_ID attempts=${attempts:-0}" \
    "threshold=$RESCUE_AFTER guard_rescue=$flag_state"

if [ ! -f "$MANIFEST" ]; then
    log_line "rescue: no deep-uninstall manifest; nothing to do"
    exit 0
fi

targets=$(deep_rescue_new_packages "$MANIFEST" "$LAST_GOOD" | tr '\n' ' ')
targets=${targets% }
if [ -z "$targets" ]; then
    log_line "rescue: no packages added since the last good boot"
    exit 0
fi
log_line "rescue targets: $targets"

ABX2XML=$(command -v abx2xml 2>/dev/null)
XML2ABX=$(command -v xml2abx 2>/dev/null)
CHCON=$(command -v chcon 2>/dev/null)
[ -n "$ABX2XML" ] || ABX2XML=/system/bin/abx2xml
[ -n "$XML2ABX" ] || XML2ABX=/system/bin/xml2abx
[ -n "$CHCON" ] || CHCON=/system/bin/chcon

for name in \
    package-restrictions.xml \
    package-restrictions-backup.xml \
    package-restrictions.xml.fallback \
    package-restrictions.xml.reservecopy; do
    if [ "$name" = "package-restrictions.xml" ]; then
        if [ -f "$RESTRICTIONS_DIR/$name" ] &&
            repair_restrictions_file "$RESTRICTIONS_DIR/$name"; then
            PRIMARY_OK=1
        else
            PRIMARY_OK=0
        fi
    else
        repair_restrictions_file "$RESTRICTIONS_DIR/$name"
    fi
done

# If the primary copy could not be repaired, system_server may still read
# inst="false". Keep the manifest intact so the next boot can retry; do not
# drop the user's backup entry on a failed repair.
if [ "$PRIMARY_OK" -ne 1 ]; then
    log_line "rescue: primary package-restrictions not repaired; leave manifest untouched"
    exit 0
fi

rollback_manifest "$targets"
log_line "deep rescue done boot=$BOOT_ID xml_changed=$RESCUE_CHANGED"
sync 2>/dev/null
exit 0
