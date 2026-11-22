package com.nemu.hibernate.core

object AntiFraudPolicy {
    private val restoreActions: Map<String, Set<AntiFraudAction>> = mapOf(
        AntiFraudTargets.NUMBER_IDENTITY to setOf(
            AntiFraudAction.UNSUSPEND,
            AntiFraudAction.ENABLE
        ),
        AntiFraudTargets.SECURITY_SERVER to setOf(
            AntiFraudAction.APP_OPS_ALLOW,
            AntiFraudAction.STANDBY_ACTIVE
        ),
        AntiFraudTargets.AWARENESS to setOf(
            AntiFraudAction.APP_OPS_ALLOW,
            AntiFraudAction.STANDBY_ACTIVE
        )
    )

    fun planApply(
        config: AntiFraudConfig,
        previous: AntiFraudConfig = AntiFraudConfig()
    ): List<AntiFraudCommand> = buildList {
        when (config.numberIdentityMode) {
            NumberIdentityMode.OFF -> Unit
            NumberIdentityMode.SUSPEND -> {
                add(
                    command(
                        AntiFraudTargets.NUMBER_IDENTITY,
                        AntiFraudAction.SUSPEND,
                        CommandBuilder.suspend(AntiFraudTargets.NUMBER_IDENTITY)
                    )
                )
            }
            NumberIdentityMode.DISABLE_USER -> add(
                command(
                    AntiFraudTargets.NUMBER_IDENTITY,
                    AntiFraudAction.DISABLE_USER,
                    CommandBuilder.disableUser(AntiFraudTargets.NUMBER_IDENTITY)
                )
            )
        }
        if (config.restrictSecurityServer) {
            addBackgroundRestrict(AntiFraudTargets.SECURITY_SERVER)
        } else if (previous.restrictSecurityServer) {
            addBackgroundRestore(AntiFraudTargets.SECURITY_SERVER)
        }
        if (config.restrictAwareness) {
            addBackgroundRestrict(AntiFraudTargets.AWARENESS)
        } else if (previous.restrictAwareness) {
            addBackgroundRestore(AntiFraudTargets.AWARENESS)
        }
    }

    fun planRestore(numberIdentityWasEnabled: Boolean? = null): List<AntiFraudCommand> = buildList {
        add(
            command(
                AntiFraudTargets.NUMBER_IDENTITY,
                AntiFraudAction.UNSUSPEND,
                CommandBuilder.unsuspend(AntiFraudTargets.NUMBER_IDENTITY)
            )
        )
        if (numberIdentityWasEnabled == true) {
            add(
                command(
                    AntiFraudTargets.NUMBER_IDENTITY,
                    AntiFraudAction.ENABLE,
                    CommandBuilder.enable(AntiFraudTargets.NUMBER_IDENTITY)
                )
            )
        }
        addBackgroundRestore(AntiFraudTargets.SECURITY_SERVER)
        addBackgroundRestore(AntiFraudTargets.AWARENESS)
    }

    fun isAllowed(packageName: String, action: AntiFraudAction): Boolean {
        val target = AntiFraudTargets.byPackage(packageName) ?: return false
        return action in target.applyActions ||
            action in restoreActions[packageName].orEmpty()
    }

    /**
     * Always include numberidentity in the shutdown/boot safety pass.
     * `pm unsuspend` is idempotent, and `disable-user` is not affected by it.
     * Unconditional inclusion also covers the case where the user applies
     * SUSPEND and later switches the saved mode to OFF without restoring.
     */
    fun safetyPackages(): List<String> =
        listOf(AntiFraudTargets.NUMBER_IDENTITY)

    fun summarize(results: List<AntiFraudResult>): String {
        if (results.isEmpty()) return "没有需要执行的命令"
        val ok = results.count { it.ok }
        val failed = results.size - ok
        val base = if (failed == 0) {
            "成功 $ok 条"
        } else {
            "成功 $ok 条，失败 $failed 条"
        }
        val dryRun = results.all { it.dryRun }
        return if (dryRun) "$base（DRY-RUN，未执行）" else base
    }

    private fun MutableList<AntiFraudCommand>.addBackgroundRestrict(pkg: String) {
        add(
            command(
                pkg,
                AntiFraudAction.APP_OPS_IGNORE,
                CommandBuilder.appOpsBackground(pkg, restricted = true)
            )
        )
        add(
            command(
                pkg,
                AntiFraudAction.STANDBY_RESTRICTED,
                CommandBuilder.standbyBucket(pkg, restricted = true)
            )
        )
    }

    private fun MutableList<AntiFraudCommand>.addBackgroundRestore(pkg: String) {
        add(
            command(
                pkg,
                AntiFraudAction.APP_OPS_ALLOW,
                CommandBuilder.appOpsBackground(pkg, restricted = false)
            )
        )
        add(
            command(
                pkg,
                AntiFraudAction.STANDBY_ACTIVE,
                CommandBuilder.standbyBucket(pkg, restricted = false)
            )
        )
    }

    private fun command(
        pkg: String,
        action: AntiFraudAction,
        shell: String
    ): AntiFraudCommand {
        require(isAllowed(pkg, action)) { "不允许的动作：$pkg / $action" }
        return AntiFraudCommand(pkg, action, shell)
    }
}
