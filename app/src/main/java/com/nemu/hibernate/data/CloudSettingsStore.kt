package com.nemu.hibernate.data

/**
 * Cloud-module settings contract. Pure Kotlin so JVM tests can use a fake.
 * Keys are cloud-specific; the Android implementation uses the "cloud_" prefix.
 */
interface CloudSettingsStore {
    var dryRun: Boolean
    var lastResult: String?
    fun isEnabled(packageName: String): Boolean
    fun setEnabled(packageName: String, enabled: Boolean)
    fun isConfirmed(packageName: String): Boolean
    fun setConfirmed(packageName: String, confirmed: Boolean)

    /**
     * Whether the current policy for this package has been executed (or
     * attempted) since it was last changed. Detection uses this to distinguish
     * "configured but not applied yet" from a real policy/actual mismatch.
     */
    fun isApplied(packageName: String): Boolean
    fun setApplied(packageName: String, applied: Boolean)

    /** Original per-user enabled state captured before Nemu disables a package. */
    fun originalEnabled(packageName: String): Boolean?
    fun setOriginalEnabled(packageName: String, enabled: Boolean?)
}
