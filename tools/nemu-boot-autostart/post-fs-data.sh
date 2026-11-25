#!/system/bin/sh
# KernelSU/FinaSU post-fs-data: install persistent guard and count this boot.
MODDIR=${0%/*}
. "$MODDIR/install_guard.sh"
install_nemu_guard

# Last-resort deep-uninstall rescue: repair package-restrictions before
# system_server reads it, and before boot_guard.sh can disable this module.
# It is fail-soft and only touches packages in the deep-uninstall manifest.
if [ -f "$MODDIR/deep_rescue.sh" ]; then
    /system/bin/sh "$MODDIR/deep_rescue.sh" || true
fi

"$GUARD_DIR/boot_guard.sh" post-fs-data
