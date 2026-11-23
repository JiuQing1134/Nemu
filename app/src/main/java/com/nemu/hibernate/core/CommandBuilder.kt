package com.nemu.hibernate.core

object CommandBuilder {
    fun suspend(pkg: String, userId: Int = 0): String =
        "pm suspend --user $userId ${quote(pkg)}"

    fun unsuspend(pkg: String, userId: Int = 0): String =
        "pm unsuspend --user $userId ${quote(pkg)}"

    fun unsuspendAll(packages: Collection<String>, userId: Int = 0): String {
        require(packages.isNotEmpty()) { "packages must not be empty" }
        return "pm unsuspend --user $userId " +
            packages.joinToString(" ") { quote(it) }
    }

    fun forceStop(pkg: String): String =
        "am force-stop ${quote(pkg)}"

    fun disableUser(pkg: String, userId: Int = 0): String =
        "pm disable-user --user $userId ${quote(pkg)}"

    fun enable(pkg: String, userId: Int = 0): String =
        "pm enable --user $userId ${quote(pkg)}"

    /** Hides the package for the user; it looks uninstalled but keeps its data. */
    fun hide(pkg: String, userId: Int = 0): String =
        "pm hide --user $userId ${quote(pkg)}"

    fun unhide(pkg: String, userId: Int = 0): String =
        "pm unhide --user $userId ${quote(pkg)}"

    fun disableComponent(component: String, userId: Int = 0): String =
        "pm disable --user $userId ${quote(component)}"

    fun defaultStateComponent(component: String, userId: Int = 0): String =
        "pm default-state --user $userId ${quote(component)}"

    fun enableComponent(component: String, userId: Int = 0): String =
        "pm enable --user $userId ${quote(component)}"

    /**
     * Restores the component state captured before Nemu disabled it. Nemu
     * only owns components whose original state was DEFAULT (0) or ENABLED (1);
     * already-disabled components (2/3/4) are never claimed, so they fall back
     * to default-state here and are not part of an ownership record.
     */
    fun restoreComponent(component: String, originalState: Int, userId: Int = 0): String =
        when (originalState) {
            1 -> enableComponent(component, userId)
            else -> defaultStateComponent(component, userId)
        }

    fun appOpsBackground(pkg: String, restricted: Boolean, userId: Int = 0): String =
        "appops set --user $userId ${quote(pkg)} RUN_ANY_IN_BACKGROUND " +
            (if (restricted) "ignore" else "allow")

    /** Restores an arbitrary AppOps mode captured before Nemu changed it. */
    fun appOpsBackgroundMode(pkg: String, mode: String, userId: Int = 0): String =
        "appops set --user $userId ${quote(pkg)} RUN_ANY_IN_BACKGROUND ${quote(mode)}"

    fun appOpsGet(pkg: String, userId: Int = 0): String =
        "appops get --user $userId ${quote(pkg)} RUN_ANY_IN_BACKGROUND"

    fun standbyBucket(pkg: String, restricted: Boolean, userId: Int = 0): String =
        "am set-standby-bucket --user $userId ${quote(pkg)} " +
            (if (restricted) "restricted" else "active")

    /** Restores an arbitrary standby bucket captured before Nemu changed it. */
    fun standbyBucketValue(pkg: String, bucket: Int, userId: Int = 0): String =
        "am set-standby-bucket --user $userId ${quote(pkg)} $bucket"

    fun standbyGet(pkg: String, userId: Int = 0): String =
        "am get-standby-bucket --user $userId ${quote(pkg)}"

    private fun quote(value: String): String = ShellQuote.quote(value)
}
