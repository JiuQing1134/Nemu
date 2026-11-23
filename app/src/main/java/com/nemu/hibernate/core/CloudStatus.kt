package com.nemu.hibernate.core

/**
 * Read-only package state from PackageManager. It is not a promise that no
 * background work remains.
 */
data class CloudStatus(
    val enabled: Boolean,
    val suspended: Boolean
)
