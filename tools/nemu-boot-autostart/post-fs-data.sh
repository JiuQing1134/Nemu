#!/system/bin/sh
# KernelSU/FinaSU post-fs-data: install persistent guard and count this boot.
MODDIR=${0%/*}
. "$MODDIR/install_guard.sh"
install_nemu_guard
"$GUARD_DIR/boot_guard.sh" post-fs-data
