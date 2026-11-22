package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFraudPolicyTest {
    @Test
    fun defaultConfig_plansNothing() {
        assertTrue(AntiFraudPolicy.planApply(AntiFraudConfig()).isEmpty())
    }

    @Test
    fun uncheckingRestrictions_plansRestoreCommands() {
        val previous = AntiFraudConfig(
            restrictSecurityServer = true,
            restrictAwareness = true
        )

        val commands = AntiFraudPolicy.planApply(AntiFraudConfig(), previous)

        assertEquals(4, commands.size)
        assertTrue(
            commands.any {
                it.packageName == AntiFraudTargets.SECURITY_SERVER &&
                    it.action == AntiFraudAction.APP_OPS_ALLOW
            }
        )
        assertTrue(
            commands.any {
                it.packageName == AntiFraudTargets.AWARENESS &&
                    it.action == AntiFraudAction.STANDBY_ACTIVE
            }
        )
    }

    @Test
    fun numberIdentitySuspend_doesNotEnableBeforeSuspending() {
        val commands = AntiFraudPolicy.planApply(
            AntiFraudConfig(numberIdentityMode = NumberIdentityMode.SUSPEND)
        )
        assertEquals(1, commands.size)
        assertEquals(
            listOf(
                "pm suspend --user 0 'com.hihonor.numberidentity'"
            ),
            commands.map { it.shell }
        )
        assertEquals(AntiFraudAction.SUSPEND, commands[0].action)
    }

    @Test
    fun numberIdentityDisable_plansDisableUserNotSuspend() {
        val commands = AntiFraudPolicy.planApply(
            AntiFraudConfig(numberIdentityMode = NumberIdentityMode.DISABLE_USER)
        )
        assertEquals(1, commands.size)
        assertEquals(AntiFraudAction.DISABLE_USER, commands[0].action)
        assertEquals(
            "pm disable-user --user 0 'com.hihonor.numberidentity'",
            commands[0].shell
        )
        assertFalse(commands.any { it.shell.startsWith("pm suspend") })
    }

    @Test
    fun restrictingSecurityServer_usesOnlyAppOpsAndStandby() {
        val commands = AntiFraudPolicy.planApply(
            AntiFraudConfig(restrictSecurityServer = true)
        )
        assertEquals(2, commands.size)
        assertEquals(
            listOf(
                "appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND ignore",
                "am set-standby-bucket --user 0 'com.hihonor.securityserver' restricted"
            ),
            commands.map { it.shell }
        )
        assertFalse(commands.any { it.shell.startsWith("pm suspend") })
        assertFalse(commands.any { it.shell.startsWith("pm disable-user") })
    }

    @Test
    fun restrictingAwareness_usesOnlyAppOpsAndStandby() {
        val commands = AntiFraudPolicy.planApply(
            AntiFraudConfig(restrictAwareness = true)
        )
        assertEquals(2, commands.size)
        assertEquals(
            listOf(
                "appops set --user 0 'com.hihonor.awareness' RUN_ANY_IN_BACKGROUND ignore",
                "am set-standby-bucket --user 0 'com.hihonor.awareness' restricted"
            ),
            commands.map { it.shell }
        )
    }

    @Test
    fun noConfig_everPlansWholePackageActionForCoreSecurityPackages() {
        for (mode in NumberIdentityMode.entries) {
            for (server in listOf(false, true)) {
                for (awareness in listOf(false, true)) {
                    val commands = AntiFraudPolicy.planApply(
                        AntiFraudConfig(mode, server, awareness)
                    )
                    for (target in listOf(
                        AntiFraudTargets.SECURITY_SERVER,
                        AntiFraudTargets.AWARENESS
                    )) {
                        val targetCommands = commands.filter { it.packageName == target }
                        assertFalse(
                            "$target must not receive whole-package actions",
                            targetCommands.any {
                                it.action == AntiFraudAction.SUSPEND ||
                                    it.action == AntiFraudAction.DISABLE_USER
                            }
                        )
                        assertFalse(
                            "$target must not receive force-stop or clear",
                            targetCommands.any {
                                it.shell.startsWith("am force-stop") ||
                                    it.shell.startsWith("pm clear")
                            }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun securityPluginBaseIsNeverTouched() {
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.DISABLE_USER,
            restrictSecurityServer = true,
            restrictAwareness = true
        )
        assertTrue(
            AntiFraudPolicy.planApply(config)
                .none { it.packageName == AntiFraudTargets.SECURITY_PLUGIN_BASE }
        )
        assertTrue(
            AntiFraudPolicy.planRestore()
                .none { it.packageName == AntiFraudTargets.SECURITY_PLUGIN_BASE }
        )
    }

    @Test
    fun restore_onlyReenablesNumberIdentityWhenItWasEnabledBeforeNemu() {
        assertEquals(
            listOf(
                "pm unsuspend --user 0 'com.hihonor.numberidentity'",
                "appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket --user 0 'com.hihonor.securityserver' active",
                "appops set --user 0 'com.hihonor.awareness' RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket --user 0 'com.hihonor.awareness' active"
            ),
            AntiFraudPolicy.planRestore(numberIdentityWasEnabled = false)
                .map { it.shell }
        )
        assertTrue(
            AntiFraudPolicy.planRestore(numberIdentityWasEnabled = true)
                .any { it.action == AntiFraudAction.ENABLE }
        )
    }

    @Test
    fun guard_rejectsWholePackageActionsForCoreSecurityPackages() {
        assertFalse(
            AntiFraudPolicy.isAllowed(
                AntiFraudTargets.SECURITY_SERVER,
                AntiFraudAction.SUSPEND
            )
        )
        assertFalse(
            AntiFraudPolicy.isAllowed(
                AntiFraudTargets.AWARENESS,
                AntiFraudAction.DISABLE_USER
            )
        )
        assertFalse(
            AntiFraudPolicy.isAllowed(
                AntiFraudTargets.SECURITY_PLUGIN_BASE,
                AntiFraudAction.APP_OPS_IGNORE
            )
        )
        assertFalse(
            AntiFraudPolicy.isAllowed("com.example.unknown", AntiFraudAction.SUSPEND)
        )
        assertTrue(
            AntiFraudPolicy.isAllowed(
                AntiFraudTargets.NUMBER_IDENTITY,
                AntiFraudAction.DISABLE_USER
            )
        )
        assertTrue(
            AntiFraudPolicy.isAllowed(
                AntiFraudTargets.SECURITY_SERVER,
                AntiFraudAction.APP_OPS_IGNORE
            )
        )
    }

    @Test
    fun safetyPackages_alwaysIncludeNumberIdentity() {
        // Unconditional inclusion closes the gap where the user applies
        // SUSPEND, then changes the saved mode to OFF without restoring:
        // the package is still suspended and must be unfrozen on shutdown.
        // pm unsuspend is idempotent; disable-user is not affected by it.
        assertEquals(
            listOf(AntiFraudTargets.NUMBER_IDENTITY),
            AntiFraudPolicy.safetyPackages()
        )
    }

    @Test
    fun summarize_countsSuccessFailureAndDryRun() {
        assertEquals(
            "成功 2 条，失败 1 条",
            AntiFraudPolicy.summarize(
                listOf(result(ok = true), result(ok = false), result(ok = true))
            )
        )
        assertEquals(
            "成功 2 条（DRY-RUN，未执行）",
            AntiFraudPolicy.summarize(
                listOf(
                    result(ok = true, dryRun = true),
                    result(ok = true, dryRun = true)
                )
            )
        )
        assertTrue(AntiFraudPolicy.summarize(emptyList()).contains("没有"))
    }

    private fun result(ok: Boolean, dryRun: Boolean = false) = AntiFraudResult(
        packageName = AntiFraudTargets.NUMBER_IDENTITY,
        action = AntiFraudAction.SUSPEND,
        shell = "pm suspend --user 0 'com.hihonor.numberidentity'",
        ok = ok,
        dryRun = dryRun,
        output = ""
    )
}
