#!/system/bin/sh
# Install the persistent guard outside the disableable Nemu module.
# Called from the module's post-fs-data.sh and service.sh.
MODDIR=${MODDIR:-${0%/*}}
GUARD_DIR=${NEMU_GUARD_DIR:-/data/adb/nemu/boot_guard}
GUARD_HOOK=/data/adb/nemu/boot_guard/boot_guard.sh

install_owned_hook() {
    hook_path=$1
    hook_mode=$2
    temp_path="${hook_path}.tmp.$$"
    printf '#!/system/bin/sh\nexec %s %s\n' "$GUARD_HOOK" "$hook_mode" \
        > "$temp_path" || return 1

    # Replace Nemu-owned historical hooks (different guard path or CRLF)
    # but never a user-created file that merely shares the filename.
    if [ -e "$hook_path" ] && ! cmp -s "$temp_path" "$hook_path"; then
        lines=$(wc -l < "$hook_path" 2>/dev/null | tr -d ' ')
        first=$(sed -n '1p' "$hook_path" 2>/dev/null | tr -d '\015')
        second=$(sed -n '2p' "$hook_path" 2>/dev/null | tr -d '\015')
        managed=0
        if [ "$lines" = "2" ] &&
            [ "$first" = "#!/system/bin/sh" ] &&
            printf '%s\n' "$second" |
                grep -Eq "^exec [^ ]*boot_guard\.sh $hook_mode$"; then
            managed=1
        fi
        if [ "$managed" -ne 1 ]; then
            rm -f "$temp_path"
            echo "Refusing to replace unmanaged hook: $hook_path" >&2
            return 1
        fi
    fi
    chmod 755 "$temp_path" || {
        rm -f "$temp_path"
        return 1
    }
    mv -f "$temp_path" "$hook_path" || {
        rm -f "$temp_path"
        return 1
    }
}

install_nemu_guard() {
    mkdir -p "$GUARD_DIR" /data/adb/post-fs-data.d /data/adb/service.d || return 1
    chmod 700 /data/adb/nemu "$GUARD_DIR" 2>/dev/null || return 1

    temp_guard="$GUARD_DIR/boot_guard.sh.tmp.$$"
    # Normalize the installed interpreter script even if the source archive
    # was repacked on Windows with CRLF line endings.
    tr -d '\015' < "$MODDIR/boot_guard.sh" > "$temp_guard" || return 1
    chmod 755 "$temp_guard" && mv -f "$temp_guard" "$GUARD_HOOK" || {
        rm -f "$temp_guard"
        return 1
    }
    chmod 700 "$GUARD_DIR" || return 1

    install_owned_hook /data/adb/post-fs-data.d/00nemu_boot_guard.sh post-fs-data || return 1
    install_owned_hook /data/adb/service.d/00nemu_boot_guard.sh service || return 1
    sync 2>/dev/null
}
