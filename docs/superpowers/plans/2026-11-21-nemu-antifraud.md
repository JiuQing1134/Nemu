# Nemu Anti-Fraud Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separate, default-off anti-fraud module to Nemu that can (a) optionally `suspend` or `disable-user` `com.hihonor.numberidentity`, (b) restrict background/standby for `com.hihonor.securityserver` and `com.hihonor.awareness` using only `appops` + `am set-standby-bucket`, (c) observe `com.hihonor.securitypluginbase` without any action, and (d) restore every change with one confirmed action — without ever whole-package disabling `securityserver` or `awareness`.

**Architecture:** Pure Kotlin command planning (`AntiFraudPolicy`) is unit-tested on the JVM. A thin `AntiFraudController` executes the planned commands through the existing `PrivilegeBackend` (Root or Shizuku), respecting the existing Dry-run setting. A new `AntiFraudSettings` class stores module intent under `antifraud_*` keys in the existing `nemu` SharedPreferences file. The UI is a dedicated Compose section, separate from `Targets.all`; every risky apply requires an explicit confirmation dialog, and `disable-user` additionally requires a checkbox acknowledgement. `SafetyUnfreezer` is extended so a suspended `numberidentity` is unsuspended on normal shutdown/reboot, matching the project-wide "never carry suspend across reboot" rule.

**Tech Stack:** Kotlin 2.4.20, AGP 9.1.0, Gradle 9.7.1 wrapper, Compose BOM 2025.12.01, Material3, WorkManager 2.11.0, Shizuku API/provider 13.1.5, JUnit 4.13.2.

**Spec:** `D:\win_root\nemu\docs\03-design.md` §7.2. Package facts: `D:\win_root\nemu\docs\02-package-inventory.md` §3. Device tests: `D:\win_root\nemu\docs\04-test-plan.md`.

**Baseline:** branch `main`, commit `fdc3ab1`.

---

## Global Constraints

- **No whole-package disable/suspend for `com.hihonor.securityserver` or `com.hihonor.awareness`.** The only commands allowed for these two packages are:
  - `appops set --user 0 '<pkg>' RUN_ANY_IN_BACKGROUND ignore|allow`
  - `am set-standby-bucket --user 0 '<pkg>' restricted|active`
- **No `pm uninstall` anywhere.** `pm disable-user` is allowed only in `AntiFraudPolicy` for `com.hihonor.numberidentity`, with a checkbox acknowledgement in the UI.
- **No Hail dependency/API/Intent.** Do not import `com.aistra.hail.*`, send Hail intents, or read Hail data.
- **No writes to the phone by the assistant.** Build/unit tests only. Installation and all device commands are executed by the user.
- **No auto re-apply after reboot.** The anti-fraud module follows the core rule: after a normal reboot, targets stay in whatever state the OS left them; the user reapplies manually.
- **One module per file.** Anti-fraud code must not be added to `Targets.all`, `Targets.kt`, `FreezeController`, or the update/AI cards.
- UI strings are Chinese; code identifiers are English.
- Package name: `com.nemu.hibernate`. `minSdk 30`, `targetSdk 36`, compile SDK 37.

## Out of Scope

- Carrier DM trio `disable-user` (`com.hihonor.regservice`, `com.hihonor.android.UEInfoCheck`, `com.hihonor.android.AutoRegSms`) — separate cloud/telemetry plan.
- `hiview*`, `cipservice`, push agent — separate plan.
- COTA/thermal `/data/cota/para` — separate root-only plan.
- Package-manager state readback in the app (installed/suspended/enabled). The UI reports command results, not live package state.
- Automatic reapplication after system updates or reboots.

## Command Matrix (authoritative)

| Package | User action | Exact commands | Forbidden |
|---|---|---|---|
| `com.hihonor.numberidentity` | 不处理 | none | — |
| `com.hihonor.numberidentity` | 暂停 | `pm enable --user 0 'com.hihonor.numberidentity'` then `pm suspend --user 0 'com.hihonor.numberidentity'` | `pm uninstall` |
| `com.hihonor.numberidentity` | 停用（更强） | `pm disable-user --user 0 'com.hihonor.numberidentity'` | `pm uninstall` |
| `com.hihonor.numberidentity` | 恢复 | `pm unsuspend --user 0 'com.hihonor.numberidentity'` then `pm enable --user 0 'com.hihonor.numberidentity'` | `pm uninstall` |
| `com.hihonor.securityserver` | 限制后台/待机 | `appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND ignore` then `am set-standby-bucket --user 0 'com.hihonor.securityserver' restricted` | any `pm suspend`, `pm disable-user`, `am force-stop`, `pm clear` |
| `com.hihonor.securityserver` | 恢复 | `appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND allow` then `am set-standby-bucket --user 0 'com.hihonor.securityserver' active` | any whole-package action |
| `com.hihonor.awareness` | 限制后台/待机 | `appops set --user 0 'com.hihonor.awareness' RUN_ANY_IN_BACKGROUND ignore` then `am set-standby-bucket --user 0 'com.hihonor.awareness' restricted` | any `pm suspend`, `pm disable-user`, `am force-stop`, `pm clear` |
| `com.hihonor.awareness` | 恢复 | `appops set --user 0 'com.hihonor.awareness' RUN_ANY_IN_BACKGROUND allow` then `am set-standby-bucket --user 0 'com.hihonor.awareness' active` | any whole-package action |
| `com.hihonor.securitypluginbase` | observe only | none | every action |

`userId` is always `0`. All package names are single-quoted by `CommandBuilder.quote`.

The `pm enable` before `pm suspend` clears a previous `disable-user` state. Without it, switching directly from 停用 to 暂停 without restoring would leave the package disabled and `pm suspend` would be a silent no-op. `pm enable` does not clear suspend, so the sequence is safe when the package is already suspended.

## File Structure

New files:

```
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraud.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraudPolicy.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\data\AntiFraudSettings.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\AntiFraudController.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\AntiFraudSection.kt
D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\AntiFraudPolicyTest.kt
D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\AntiFraudControllerTest.kt
```

Modified files:

```
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\DenyList.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\SafetyUnfreezer.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\ShutdownSafety.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootWorker.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\MainScreen.kt
D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\MainActivity.kt
D:\win_root\nemu\app\src\main\AndroidManifest.xml
D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\DenyListTest.kt
D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\TargetsTest.kt
D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\SafetyUnfreezerTest.kt
D:\win_root\nemu\README.md
D:\win_root\nemu\docs\03-design.md
D:\win_root\nemu\docs\04-test-plan.md
D:\win_root\nemu\docs\02-package-inventory.md
```

No Gradle dependency changes.

---

### Task 1: Anti-fraud model, action matrix, and policy (TDD)

**Files:**
- Create: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraud.kt`
- Create: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraudPolicy.kt`
- Test: `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\AntiFraudPolicyTest.kt`

**Interfaces:**
- Consumes: `CommandBuilder` (existing), `RiskLevel` (existing).
- Produces:
  - `enum class NumberIdentityMode { OFF, SUSPEND, DISABLE_USER }`
  - `data class AntiFraudConfig(numberIdentityMode: NumberIdentityMode = OFF, restrictSecurityServer: Boolean = false, restrictAwareness: Boolean = false)` with `val isActive: Boolean`.
  - `enum class AntiFraudAction` with labels: `SUSPEND`, `DISABLE_USER`, `UNSUSPEND`, `ENABLE`, `APP_OPS_IGNORE`, `APP_OPS_ALLOW`, `STANDBY_RESTRICTED`, `STANDBY_ACTIVE`.
  - `data class AntiFraudCommand(packageName, action, shell)`
  - `data class AntiFraudResult(packageName, action, shell, ok, dryRun, output)`
  - `data class AntiFraudTarget(packageName, label, risk, applyActions, note)`
  - `object AntiFraudTargets` with constants `NUMBER_IDENTITY`, `SECURITY_SERVER`, `AWARENESS`, `SECURITY_PLUGIN_BASE`; `all: List<AntiFraudTarget>`; `byPackage(pkg): AntiFraudTarget?`.
  - `object AntiFraudPolicy` with `planApply(config)`, `planRestore()`, `isAllowed(pkg, action)`, `safetyPackages()`, `summarize(results)`.

- [ ] **Step 1: Write the failing policy tests first**

Create `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\AntiFraudPolicyTest.kt`:

```kotlin
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
    fun numberIdentitySuspend_clearsDisableThenSuspends() {
        val commands = AntiFraudPolicy.planApply(
            AntiFraudConfig(numberIdentityMode = NumberIdentityMode.SUSPEND)
        )
        assertEquals(2, commands.size)
        assertEquals(
            listOf(
                "pm enable --user 0 'com.hihonor.numberidentity'",
                "pm suspend --user 0 'com.hihonor.numberidentity'"
            ),
            commands.map { it.shell }
        )
        assertEquals(AntiFraudAction.ENABLE, commands[0].action)
        assertEquals(AntiFraudAction.SUSPEND, commands[1].action)
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
    fun restore_plansUnsuspendEnableAndBackgroundAllowActive() {
        assertEquals(
            listOf(
                "pm unsuspend --user 0 'com.hihonor.numberidentity'",
                "pm enable --user 0 'com.hihonor.numberidentity'",
                "appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket --user 0 'com.hihonor.securityserver' active",
                "appops set --user 0 'com.hihonor.awareness' RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket --user 0 'com.hihonor.awareness' active"
            ),
            AntiFraudPolicy.planRestore().map { it.shell }
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
```

- [ ] **Step 2: Run the tests to verify they fail**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.AntiFraudPolicyTest"
```

Expected: compilation fails because `AntiFraudConfig`, `AntiFraudPolicy`, etc. do not exist.

- [ ] **Step 3: Implement the model**

Create `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraud.kt`:

```kotlin
package com.nemu.hibernate.core

enum class NumberIdentityMode {
    OFF,
    SUSPEND,
    DISABLE_USER
}

data class AntiFraudConfig(
    val numberIdentityMode: NumberIdentityMode = NumberIdentityMode.OFF,
    val restrictSecurityServer: Boolean = false,
    val restrictAwareness: Boolean = false
) {
    val isActive: Boolean
        get() = numberIdentityMode != NumberIdentityMode.OFF ||
            restrictSecurityServer ||
            restrictAwareness
}

enum class AntiFraudAction(val label: String) {
    SUSPEND("暂停"),
    DISABLE_USER("停用"),
    UNSUSPEND("取消暂停"),
    ENABLE("启用"),
    APP_OPS_IGNORE("限制后台"),
    APP_OPS_ALLOW("恢复后台"),
    STANDBY_RESTRICTED("限制待机"),
    STANDBY_ACTIVE("恢复待机")
}

data class AntiFraudCommand(
    val packageName: String,
    val action: AntiFraudAction,
    val shell: String
)

data class AntiFraudResult(
    val packageName: String,
    val action: AntiFraudAction,
    val shell: String,
    val ok: Boolean,
    val dryRun: Boolean,
    val output: String
)

data class AntiFraudTarget(
    val packageName: String,
    val label: String,
    val risk: RiskLevel,
    val applyActions: Set<AntiFraudAction>,
    val note: String
)

object AntiFraudTargets {
    const val NUMBER_IDENTITY = "com.hihonor.numberidentity"
    const val SECURITY_SERVER = "com.hihonor.securityserver"
    const val AWARENESS = "com.hihonor.awareness"
    const val SECURITY_PLUGIN_BASE = "com.hihonor.securitypluginbase"

    val all: List<AntiFraudTarget> = listOf(
        AntiFraudTarget(
            packageName = NUMBER_IDENTITY,
            label = "号码识别",
            risk = RiskLevel.YELLOW,
            applyActions = setOf(
                AntiFraudAction.SUSPEND,
                AntiFraudAction.DISABLE_USER
            ),
            note = "暂停或停用后失去来电识别/骚扰与诈骗标记；可恢复。"
        ),
        AntiFraudTarget(
            packageName = SECURITY_SERVER,
            label = "安全公共服务",
            risk = RiskLevel.RED,
            applyActions = setOf(
                AntiFraudAction.APP_OPS_IGNORE,
                AntiFraudAction.STANDBY_RESTRICTED
            ),
            note = "反诈核心。仅限制后台/待机，禁止整包暂停或停用。"
        ),
        AntiFraudTarget(
            packageName = AWARENESS,
            label = "情景感知",
            risk = RiskLevel.RED,
            applyActions = setOf(
                AntiFraudAction.APP_OPS_IGNORE,
                AntiFraudAction.STANDBY_RESTRICTED
            ),
            note = "反诈与人脸活体/深伪检测载体。仅限制后台/待机，禁止整包暂停或停用。"
        ),
        AntiFraudTarget(
            packageName = SECURITY_PLUGIN_BASE,
            label = "安全插件底座",
            risk = RiskLevel.YELLOW,
            applyActions = emptySet(),
            note = "仅观察，不提供任何一键操作。"
        )
    )

    fun byPackage(packageName: String): AntiFraudTarget? =
        all.firstOrNull { it.packageName == packageName }
}
```

Create `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\AntiFraudPolicy.kt`:

```kotlin
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

    fun planApply(config: AntiFraudConfig): List<AntiFraudCommand> = buildList {
        when (config.numberIdentityMode) {
            NumberIdentityMode.OFF -> Unit
            NumberIdentityMode.SUSPEND -> {
                add(
                    command(
                        AntiFraudTargets.NUMBER_IDENTITY,
                        AntiFraudAction.ENABLE,
                        CommandBuilder.enable(AntiFraudTargets.NUMBER_IDENTITY)
                    )
                )
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
        }
        if (config.restrictAwareness) {
            addBackgroundRestrict(AntiFraudTargets.AWARENESS)
        }
    }

    fun planRestore(): List<AntiFraudCommand> = buildList {
        add(
            command(
                AntiFraudTargets.NUMBER_IDENTITY,
                AntiFraudAction.UNSUSPEND,
                CommandBuilder.unsuspend(AntiFraudTargets.NUMBER_IDENTITY)
            )
        )
        add(
            command(
                AntiFraudTargets.NUMBER_IDENTITY,
                AntiFraudAction.ENABLE,
                CommandBuilder.enable(AntiFraudTargets.NUMBER_IDENTITY)
            )
        )
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
```

- [ ] **Step 4: Run the policy tests**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.AntiFraudPolicyTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/core/AntiFraud.kt app/src/main/java/com/nemu/hibernate/core/AntiFraudPolicy.kt app/src/test/java/com/nemu/hibernate/core/AntiFraudPolicyTest.kt
git commit -m "feat: add anti-fraud action matrix and command policy"
```

---

### Task 2: Harden the generic freeze guard

**Files:**
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\DenyList.kt`
- Modify: `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\DenyListTest.kt`
- Modify: `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\TargetsTest.kt`

**Interfaces:**
- Consumes: `AntiFraudTargets` from Task 1.
- Produces: `DenyList.isProtected` returns `true` for all four anti-fraud packages. `FreezeController` therefore refuses them even if a future change accidentally adds them to `Targets.all`. The anti-fraud controller does **not** consult `DenyList`; it uses `AntiFraudPolicy.isAllowed`.

- [ ] **Step 1: Add failing tests**

Append to `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\DenyListTest.kt`:

```kotlin
    @Test
    fun antiFraudPackages_are_protectedFromGenericFreeze() {
        assertTrue(DenyList.isProtected("com.hihonor.numberidentity"))
        assertTrue(DenyList.isProtected("com.hihonor.securityserver"))
        assertTrue(DenyList.isProtected("com.hihonor.awareness"))
        assertTrue(DenyList.isProtected("com.hihonor.securitypluginbase"))
    }
```

Append to `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\core\TargetsTest.kt`:

```kotlin
    @Test
    fun genericTargets_doNotIncludeAntiFraudPackages() {
        val antiFraud = AntiFraudTargets.all.map { it.packageName }.toSet()
        Targets.all.forEach { target ->
            assertFalse(
                "anti-fraud package must stay out of Targets.all: ${target.packageName}",
                target.packageName in antiFraud
            )
        }
    }
```

`TargetsTest.kt` already imports `assertFalse`; keep it.

- [ ] **Step 2: Run the tests to verify the new deny-list test fails**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.DenyListTest"
```

Expected: `antiFraudPackages_are_protectedFromGenericFreeze` fails on `numberidentity` and `securitypluginbase`.

- [ ] **Step 3: Add the packages and the guard documentation**

Replace `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\core\DenyList.kt` with:

```kotlin
package com.nemu.hibernate.core

/**
 * Packages that must never be whole-package frozen by the generic
 * [com.nemu.hibernate.policy.FreezeController]. The anti-fraud module has its
 * own narrower action matrix ([AntiFraudPolicy]) and does not use this list:
 * for securityserver/awareness it only emits AppOps/standby commands, and for
 * numberidentity it permits the explicitly confirmed suspend/disable path.
 */
object DenyList {
    val protectedPackages: Set<String> = setOf(
        "com.hihonor.systemserver",
        "com.hihonor.systemmanager",
        "com.hihonor.devicemanager",
        "com.hihonor.aipluginengine",
        "com.hihonor.assistant",
        "com.hihonor.magicvoice",
        "com.hihonor.brain",
        "com.hihonor.awareness",
        "com.hihonor.securityserver",
        "com.hihonor.securitypluginbase",
        "com.hihonor.numberidentity",
        "com.hihonor.powergenie",
        "com.hihonor.iaware",
        "com.hihonor.android.pushagent"
    )

    fun isProtected(packageName: String): Boolean =
        packageName in protectedPackages
}
```

- [ ] **Step 4: Run the guard tests**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.DenyListTest" --tests "com.nemu.hibernate.core.TargetsTest"
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/core/DenyList.kt app/src/test/java/com/nemu/hibernate/core/DenyListTest.kt app/src/test/java/com/nemu/hibernate/core/TargetsTest.kt
git commit -m "feat: protect anti-fraud packages from generic freeze"
```

---

### Task 3: Anti-fraud settings and controller

**Files:**
- Create: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\data\AntiFraudSettings.kt`
- Create: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\AntiFraudController.kt`
- Test: `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\AntiFraudControllerTest.kt`

**Interfaces:**
- Consumes: `AntiFraudPolicy`, `AntiFraudConfig`, `AntiFraudCommand`, `AntiFraudResult`, `AntiFraudAction`, `AntiFraudTargets`, `Settings.dryRun`, `PrivilegeBackend`, `ActionLog`.
- Produces:
  - `class AntiFraudSettings(context: Context)` with:
    - `var numberIdentityMode: NumberIdentityMode`
    - `var restrictSecurityServer: Boolean`
    - `var restrictAwareness: Boolean`
    - `var lastResult: String?`
    - `fun config(): AntiFraudConfig`
    - `fun save(config: AntiFraudConfig)`
    - `fun reset()`
  - `class AntiFraudController(backend: PrivilegeBackend, dryRun: Boolean)` with:
    - `suspend fun apply(config: AntiFraudConfig): List<AntiFraudResult>`
    - `suspend fun restoreAll(): List<AntiFraudResult>`
    - `internal suspend fun execute(commands: List<AntiFraudCommand>): List<AntiFraudResult>`

**Storage contract (no collision with `Settings`):**

| Key | Type | Default |
|---|---|---|
| `antifraud_numberidentity_mode` | String enum name | `OFF` |
| `antifraud_securityserver_restrict` | Boolean | `false` |
| `antifraud_awareness_restrict` | Boolean | `false` |
| `antifraud_last_result` | String | `null` |

All keys live in the existing `SharedPreferences("nemu", MODE_PRIVATE)` file, but the `antifraud_` prefix avoids the existing `target_<pkg>` keys.

- [ ] **Step 1: Write the failing controller tests first**

Create `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\AntiFraudControllerTest.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AntiFraudAction
import com.nemu.hibernate.core.AntiFraudCommand
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFraudControllerTest {
    private class FakeBackend(
        private val failCommandContaining: String? = null
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return if (failCommandContaining != null &&
                command.contains(failCommandContaining)
            ) {
                ShellResult(1, "boom")
            } else {
                ShellResult(0, "")
            }
        }
    }

    @Test
    fun dryRun_reportsPlannedCommandsWithoutExecuting() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = true)
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.SUSPEND,
            restrictSecurityServer = true
        )

        val results = controller.apply(config)

        assertTrue(backend.commands.isEmpty())
        assertEquals(AntiFraudPolicy.planApply(config).size, results.size)
        assertTrue(results.all { it.dryRun && it.ok })
    }

    @Test
    fun apply_executesPlannedCommandsInOrder() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.DISABLE_USER,
            restrictAwareness = true
        )

        val results = controller.apply(config)

        assertEquals(
            AntiFraudPolicy.planApply(config).map { it.shell },
            backend.commands
        )
        assertTrue(results.all { it.ok && !it.dryRun })
    }

    @Test
    fun apply_reportsFailureAndContinuesWithRemainingCommands() = runBlocking {
        val backend = FakeBackend(failCommandContaining = "pm suspend")
        val controller = AntiFraudController(backend, dryRun = false)
        val config = AntiFraudConfig(
            numberIdentityMode = NumberIdentityMode.SUSPEND,
            restrictSecurityServer = true
        )

        val results = controller.apply(config)

        assertEquals(4, results.size)
        assertTrue(results[0].ok)
        assertFalse(results[1].ok)
        assertTrue(results.drop(2).all { it.ok })
        assertEquals(4, backend.commands.size)
    }

    @Test
    fun restoreAll_executesRestorePlan() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)

        val results = controller.restoreAll()

        assertEquals(AntiFraudPolicy.planRestore().map { it.shell }, backend.commands)
        assertEquals(6, results.size)
        assertTrue(results.all { it.ok })
    }

    @Test
    fun execute_rejectsWholePackageDisableForSecurityServer() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)
        val forbidden = AntiFraudCommand(
            packageName = AntiFraudTargets.SECURITY_SERVER,
            action = AntiFraudAction.DISABLE_USER,
            shell = "pm disable-user --user 0 'com.hihonor.securityserver'"
        )

        val results = controller.execute(listOf(forbidden))

        assertTrue(backend.commands.isEmpty())
        assertEquals(1, results.size)
        assertFalse(results[0].ok)
        assertTrue(results[0].output.contains("未授权"))
    }

    @Test
    fun execute_rejectsSecurityPluginBase() = runBlocking {
        val backend = FakeBackend()
        val controller = AntiFraudController(backend, dryRun = false)
        val forbidden = AntiFraudCommand(
            packageName = AntiFraudTargets.SECURITY_PLUGIN_BASE,
            action = AntiFraudAction.APP_OPS_IGNORE,
            shell = "appops set --user 0 'com.hihonor.securitypluginbase' " +
                "RUN_ANY_IN_BACKGROUND ignore"
        )

        val results = controller.execute(listOf(forbidden))

        assertTrue(backend.commands.isEmpty())
        assertFalse(results[0].ok)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.AntiFraudControllerTest"
```

Expected: compilation fails because `AntiFraudController` does not exist.

- [ ] **Step 3: Implement `AntiFraudSettings`**

Create `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\data\AntiFraudSettings.kt`:

```kotlin
package com.nemu.hibernate.data

import android.content.Context
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.NumberIdentityMode

class AntiFraudSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)

    var numberIdentityMode: NumberIdentityMode
        get() = runCatching {
            NumberIdentityMode.valueOf(
                prefs.getString(
                    KEY_NUMBER_IDENTITY_MODE,
                    NumberIdentityMode.OFF.name
                ) ?: NumberIdentityMode.OFF.name
            )
        }.getOrDefault(NumberIdentityMode.OFF)
        set(value) = prefs.edit()
            .putString(KEY_NUMBER_IDENTITY_MODE, value.name)
            .apply()

    var restrictSecurityServer: Boolean
        get() = prefs.getBoolean(KEY_RESTRICT_SECURITY_SERVER, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_RESTRICT_SECURITY_SERVER, value)
            .apply()

    var restrictAwareness: Boolean
        get() = prefs.getBoolean(KEY_RESTRICT_AWARENESS, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_RESTRICT_AWARENESS, value)
            .apply()

    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    fun config(): AntiFraudConfig = AntiFraudConfig(
        numberIdentityMode = numberIdentityMode,
        restrictSecurityServer = restrictSecurityServer,
        restrictAwareness = restrictAwareness
    )

    fun save(config: AntiFraudConfig) {
        prefs.edit()
            .putString(KEY_NUMBER_IDENTITY_MODE, config.numberIdentityMode.name)
            .putBoolean(KEY_RESTRICT_SECURITY_SERVER, config.restrictSecurityServer)
            .putBoolean(KEY_RESTRICT_AWARENESS, config.restrictAwareness)
            .apply()
    }

    fun reset() = save(AntiFraudConfig())

    companion object {
        private const val KEY_NUMBER_IDENTITY_MODE = "antifraud_numberidentity_mode"
        private const val KEY_RESTRICT_SECURITY_SERVER =
            "antifraud_securityserver_restrict"
        private const val KEY_RESTRICT_AWARENESS = "antifraud_awareness_restrict"
        private const val KEY_LAST_RESULT = "antifraud_last_result"
    }
}
```

- [ ] **Step 4: Implement `AntiFraudController`**

Create `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\AntiFraudController.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AntiFraudCommand
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend

class AntiFraudController(
    private val backend: PrivilegeBackend,
    private val dryRun: Boolean
) {
    suspend fun apply(config: AntiFraudConfig): List<AntiFraudResult> {
        val results = execute(AntiFraudPolicy.planApply(config))
        ActionLog.info("反诈设置：${AntiFraudPolicy.summarize(results)}")
        return results
    }

    suspend fun restoreAll(): List<AntiFraudResult> {
        val results = execute(AntiFraudPolicy.planRestore())
        ActionLog.info("恢复反诈设置：${AntiFraudPolicy.summarize(results)}")
        return results
    }

    internal suspend fun execute(
        commands: List<AntiFraudCommand>
    ): List<AntiFraudResult> {
        val results = ArrayList<AntiFraudResult>(commands.size)
        for (command in commands) {
            if (!AntiFraudPolicy.isAllowed(command.packageName, command.action)) {
                ActionLog.error(
                    "拒绝执行未授权反诈动作：${command.packageName} / ${command.action}"
                )
                results += AntiFraudResult(
                    packageName = command.packageName,
                    action = command.action,
                    shell = command.shell,
                    ok = false,
                    dryRun = false,
                    output = "动作未授权"
                )
                continue
            }
            if (dryRun) {
                ActionLog.info("DRY-RUN（反诈）: ${command.shell}")
                results += AntiFraudResult(
                    packageName = command.packageName,
                    action = command.action,
                    shell = command.shell,
                    ok = true,
                    dryRun = true,
                    output = ""
                )
                continue
            }
            val result = backend.exec(command.shell)
            results += AntiFraudResult(
                packageName = command.packageName,
                action = command.action,
                shell = command.shell,
                ok = result.ok,
                dryRun = false,
                output = result.output.take(500)
            )
            if (result.ok) {
                ActionLog.info("OK(${backend.name}，反诈): ${command.shell}")
            } else {
                ActionLog.error(
                    "FAIL(${backend.name}，反诈，${result.exitCode}): " +
                        "${command.shell}\n${result.output.take(500)}"
                )
            }
        }
        return results
    }
}
```

- [ ] **Step 5: Run the controller tests**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.AntiFraudControllerTest"
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/data/AntiFraudSettings.kt app/src/main/java/com/nemu/hibernate/policy/AntiFraudController.kt app/src/test/java/com/nemu/hibernate/policy/AntiFraudControllerTest.kt
git commit -m "feat: add anti-fraud settings and guarded controller"
```

---

### Task 4: Safety unfreeze integration for suspended `numberidentity`

**Files:**
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\SafetyUnfreezer.kt`
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\ShutdownSafety.kt`
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootWorker.kt`
- Test: `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\SafetyUnfreezerTest.kt`

**Interfaces:**
- Consumes: `AntiFraudPolicy.safetyPackages()`.
- Produces:
  - `SafetyUnfreezer.unsuspendPackages(backend, packages: Collection<String>): Boolean`
  - `SafetyUnfreezer.unsuspendAll(backend, targets: List<Target>): Boolean` remains and delegates.
  - Shutdown and boot safety paths unsuspend `Targets.all` packages plus `com.hihonor.numberidentity` unconditionally.

**Behavior:** `pm unsuspend` is idempotent, so including `numberidentity` unconditionally is safe. It also covers the case where the user applies `SUSPEND`, then changes the saved mode to `OFF` without restoring: the package is still suspended and must be unfrozen on shutdown. `DISABLE_USER` is not undone by `pm unsuspend`; the package stays disabled across reboot until the user restores it with `pm enable`.

- [ ] **Step 1: Add a failing safety test**

Append to `D:\win_root\nemu\app\src\test\java\com\nemu\hibernate\policy\SafetyUnfreezerTest.kt`:

```kotlin
    @Test
    fun unsuspendPackages_acceptsExtraAntiFraudPackages() = runBlocking {
        val backend = FakeBackend()
        val packages = Targets.all.map { it.packageName } +
            listOf("com.hihonor.numberidentity")

        val ok = SafetyUnfreezer.unsuspendPackages(backend, packages)

        assertTrue(ok)
        assertEquals(CommandBuilder.unsuspendAll(packages), backend.lastCommand)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.SafetyUnfreezerTest"
```

Expected: compilation fails because `unsuspendPackages` does not exist.

- [ ] **Step 3: Refactor `SafetyUnfreezer`**

Replace `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\SafetyUnfreezer.kt` with:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Always-on safety path: unsuspend every known target in one shell command.
 * This deliberately ignores [com.nemu.hibernate.data.Settings.dryRun] — safety
 * must never be disabled by the dry-run setting.
 */
object SafetyUnfreezer {
    suspend fun unsuspendAll(
        backend: PrivilegeBackend,
        targets: List<Target>
    ): Boolean = unsuspendPackages(backend, targets.map { it.packageName })

    suspend fun unsuspendPackages(
        backend: PrivilegeBackend,
        packages: Collection<String>
    ): Boolean {
        if (packages.isEmpty()) return true

        val command = CommandBuilder.unsuspendAll(packages)
        val result = backend.exec(command)
        return if (result.ok) {
            ActionLog.info("安全解冻成功（${backend.name}）：${packages.size} 个目标")
            true
        } else {
            ActionLog.error(
                "安全解冻失败（${backend.name}，${result.exitCode}）：" +
                    result.output.take(500)
            )
            false
        }
    }
}
```

- [ ] **Step 4: Build the safety package list in shutdown and boot paths**

Replace `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\ShutdownSafety.kt` with:

```kotlin
package com.nemu.hibernate.policy

import android.content.Context
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.Targets
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.BackendManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Synchronous shutdown path. Broadcast receivers on this OEM can have their
 * process killed before a coroutine runs, so we block the receiver thread for
 * at most 8s (the broadcast window is ~10s) and finish the unfreeze inline.
 */
object ShutdownSafety {
    fun runBlockingUnfreeze(context: Context): String = runCatching {
        runBlocking {
            withTimeoutOrNull(8_000) {
                val settings = Settings(context)
                val packages = Targets.all.map { it.packageName } +
                    AntiFraudPolicy.safetyPackages()
                val backend = BackendManager(settings).getForShutdown()
                if (backend == null) {
                    "无可用权限"
                } else if (SafetyUnfreezer.unsuspendPackages(backend, packages)) {
                    "成功"
                } else {
                    "失败"
                }
            } ?: "超时"
        }
    }.getOrElse { throwable ->
        "异常：${throwable.message}"
    }
}
```

Modify `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\policy\BootWorker.kt`:

Add import:

```kotlin
import com.nemu.hibernate.core.AntiFraudPolicy
```

In `doWork()`, after `val settings = Settings(applicationContext)`, add:

```kotlin
        val packages = Targets.all.map { it.packageName } +
            AntiFraudPolicy.safetyPackages()
```

Replace the final call:

```kotlin
        val ok = SafetyUnfreezer.unsuspendAll(backend, Targets.all)
```

with:

```kotlin
        val ok = SafetyUnfreezer.unsuspendPackages(backend, packages)
```

- [ ] **Step 5: Run the safety tests**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.SafetyUnfreezerTest"
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt app/src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt app/src/main/java/com/nemu/hibernate/policy/BootWorker.kt app/src/test/java/com/nemu/hibernate/policy/SafetyUnfreezerTest.kt
git commit -m "feat: unsuspend anti-fraud numberidentity on shutdown"
```

---

### Task 5: UI section and wiring

**Files:**
- Create: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\AntiFraudSection.kt`
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\MainScreen.kt`
- Modify: `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\MainActivity.kt`
- Modify: `D:\win_root\nemu\app\src\main\AndroidManifest.xml`

**Interfaces:**
- Consumes: `AntiFraudConfig`, `AntiFraudPolicy`, `AntiFraudResult`, `AntiFraudTargets`, `NumberIdentityMode`, `AntiFraudSettings`, `AntiFraudController`, `Settings.dryRun`, `BackendManager`.
- Produces: a dedicated anti-fraud card rendered after the update/AI target cards and before the log list. Buttons are disabled while `busy`. Apply requires confirmation; `disable-user` also requires the acknowledgement checkbox. Restore has its own confirmation.

- [ ] **Step 1: Add package visibility queries**

In `D:\win_root\nemu\app\src\main\AndroidManifest.xml`, inside `<queries>`, add:

```xml
        <package android:name="com.hihonor.numberidentity" />
        <package android:name="com.hihonor.securityserver" />
        <package android:name="com.hihonor.awareness" />
        <package android:name="com.hihonor.securitypluginbase" />
```

- [ ] **Step 2: Create the anti-fraud UI section**

Create `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\AntiFraudSection.kt`:

```kotlin
package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.NumberIdentityMode

data class AntiFraudUiState(
    val config: AntiFraudConfig,
    val results: List<AntiFraudResult>,
    val lastResult: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiFraudSection(
    state: AntiFraudUiState,
    busy: Boolean,
    onApply: (AntiFraudConfig) -> Unit,
    onRestore: () -> Unit
) {
    var draft by remember(state.config) { mutableStateOf(state.config) }
    var pendingApply by remember { mutableStateOf<AntiFraudConfig?>(null) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var disableAcknowledged by remember { mutableStateOf(false) }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "反诈与安全（高级，默认关闭）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "与更新/AI 列表分开管理。securityserver 与 awareness 永不整包暂停/停用，只能限制后台与待机。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "可能降低反诈、安全或人脸活体能力；系统更新后开关可能被重置。Dry run 下只记录命令（设置会保存，但不执行）。",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "号码识别（${AntiFraudTargets.NUMBER_IDENTITY}）· 黄",
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberIdentityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.numberIdentityMode == mode,
                        onClick = {
                            draft = draft.copy(numberIdentityMode = mode)
                        },
                        label = { Text(numberIdentityLabel(mode)) },
                        enabled = !busy
                    )
                }
            }
            Text(
                "暂停不阻止已绑定的系统服务；更彻底请用停用。停用后需“恢复反诈设置”。",
                style = MaterialTheme.typography.bodySmall
            )

            SecurityRestrictionRow(
                title = "安全公共服务（${AntiFraudTargets.SECURITY_SERVER}）· 红",
                checked = draft.restrictSecurityServer,
                enabled = !busy,
                description = "仅 RUN_ANY_IN_BACKGROUND ignore + standby restricted；不整包暂停/停用；反诈/安全服务可能降级。",
                onCheckedChange = {
                    draft = draft.copy(restrictSecurityServer = it)
                }
            )
            SecurityRestrictionRow(
                title = "情景感知（${AntiFraudTargets.AWARENESS}）· 红",
                checked = draft.restrictAwareness,
                enabled = !busy,
                description = "仅限制后台/待机；可能影响反诈、人脸活体/深伪。",
                onCheckedChange = {
                    draft = draft.copy(restrictAwareness = it)
                }
            )

            Text(
                "安全插件底座（${AntiFraudTargets.SECURITY_PLUGIN_BASE}）· 黄：仅观察，不提供一键操作。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "已知问题：部分机型 securityserver（libdexkit）存在开机崩溃，与本模块无关；不要归因于 Nemu。",
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pendingApply = draft },
                    enabled = !busy && draft.isActive
                ) { Text("应用反诈设置") }
                OutlinedButton(
                    onClick = { showRestoreConfirm = true },
                    enabled = !busy
                ) { Text("恢复反诈设置") }
            }
            Text(
                "把号码识别切回“不处理”不会自动恢复；请用“恢复反诈设置”。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "上次反诈操作：${state.lastResult}",
                style = MaterialTheme.typography.bodySmall
            )
            state.results.takeLast(20).forEach { result ->
                Text(resultLine(result), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pendingApply?.let { config ->
        val commands = AntiFraudPolicy.planApply(config)
        val needsDisableAck =
            config.numberIdentityMode == NumberIdentityMode.DISABLE_USER
        AlertDialog(
            onDismissRequest = {
                pendingApply = null
                disableAcknowledged = false
            },
            title = { Text("确认反诈设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "将执行 ${commands.size} 条命令：",
                        style = MaterialTheme.typography.bodySmall
                    )
                    commands.forEach { command ->
                        Text(
                            "${command.packageName} · ${command.action.label}\n" +
                                command.shell,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "这些设置可能降低反诈、安全或人脸活体能力；系统更新后可能需要重新应用。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (needsDisableAck) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = disableAcknowledged,
                                onCheckedChange = { disableAcknowledged = it }
                            )
                            Text(
                                "我确认停用号码识别，并接受失去来电识别/诈骗标记",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingApply = null
                        disableAcknowledged = false
                        onApply(config)
                    },
                    enabled = !needsDisableAck || disableAcknowledged
                ) { Text("确认执行") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingApply = null
                        disableAcknowledged = false
                    }
                ) { Text("取消") }
            }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复反诈设置") },
            text = {
                Text(
                    "将取消号码识别的暂停/停用，并把 securityserver 与 awareness 的后台与待机限制恢复为 allow/active。不会卸载任何应用。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        onRestore()
                    }
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreConfirm = false }
                ) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SecurityRestrictionRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

private fun numberIdentityLabel(mode: NumberIdentityMode): String = when (mode) {
    NumberIdentityMode.OFF -> "不处理"
    NumberIdentityMode.SUSPEND -> "暂停"
    NumberIdentityMode.DISABLE_USER -> "停用（更强）"
}

private fun resultLine(result: AntiFraudResult): String {
    val prefix = when {
        result.dryRun -> "DRY-RUN"
        result.ok -> "OK"
        else -> "FAIL"
    }
    return "$prefix · ${result.packageName} · ${result.action.label}"
}
```

- [ ] **Step 3: Add the section to `MainScreen`**

In `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\ui\MainScreen.kt`:

Add imports:

```kotlin
import com.nemu.hibernate.core.AntiFraudConfig
```

Add parameters after `onRestore`:

```kotlin
    onRestore: () -> Unit,
    antiFraudState: AntiFraudUiState,
    onAntiFraudApply: (AntiFraudConfig) -> Unit,
    onAntiFraudRestore: () -> Unit
```

Replace the existing line:

```kotlin
                        Text(
                            "只 suspend，不卸载/停用系统应用。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

with:

```kotlin
                        Text(
                            "更新/AI 目标只 suspend；反诈模块的号码识别可选暂停或停用；securityserver/awareness 永不整包暂停/停用。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

Also replace the existing Dry-run line:

```kotlin
                        Text(
                            "Dry run 只影响手动冻结，不影响安全解冻。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

with:

```kotlin
                        Text(
                            "Dry run 影响手动冻结和反诈设置，不影响安全解冻。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

After the `items(targets) { ... }` block and before `item { Text("日志", ...) }`, add:

```kotlin
            item {
                AntiFraudSection(
                    state = antiFraudState,
                    busy = busy,
                    onApply = onAntiFraudApply,
                    onRestore = onAntiFraudRestore
                )
            }
```

- [ ] **Step 4: Wire the section in `MainActivity`**

In `D:\win_root\nemu\app\src\main\java\com\nemu\hibernate\MainActivity.kt`, add imports:

```kotlin
import com.nemu.hibernate.core.AntiFraudConfig
import com.nemu.hibernate.core.AntiFraudPolicy
import com.nemu.hibernate.core.AntiFraudResult
import com.nemu.hibernate.data.AntiFraudSettings
import com.nemu.hibernate.policy.AntiFraudController
import com.nemu.hibernate.ui.AntiFraudUiState
```

Add the field:

```kotlin
    private lateinit var antiFraudSettings: AntiFraudSettings
```

Initialize it next to `settings`:

```kotlin
        settings = Settings(this)
        antiFraudSettings = AntiFraudSettings(this)
        manager = BackendManager(settings)
```

Inside `setContent`, after the existing `val logs by ActionLog.entries.collectAsState()` line, add:

```kotlin
                var antiFraudConfig by remember {
                    mutableStateOf(antiFraudSettings.config())
                }
                var antiFraudResults by remember {
                    mutableStateOf<List<AntiFraudResult>>(emptyList())
                }
                var antiFraudLastResult by remember {
                    mutableStateOf(antiFraudSettings.lastResult ?: "尚未执行")
                }
```

Extend the `MainScreen(...)` call after `onRestore = { ... }` with:

```kotlin
                    antiFraudState = AntiFraudUiState(
                        config = antiFraudConfig,
                        results = antiFraudResults,
                        lastResult = antiFraudLastResult
                    ),
                    onAntiFraudApply = { config ->
                        scope.launch {
                            busy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；反诈设置未执行")
                            } else {
                                val results = AntiFraudController(
                                    backend,
                                    settings.dryRun
                                ).apply(config)
                                antiFraudResults = results
                                antiFraudConfig = config
                                antiFraudSettings.save(config)
                                val text =
                                    "${AntiFraudPolicy.summarize(results)}" +
                                        "（${System.currentTimeMillis()}）"
                                antiFraudLastResult = text
                                antiFraudSettings.lastResult = text
                                backendStatus = manager.status()
                            }
                            busy = false
                        }
                    },
                    onAntiFraudRestore = {
                        scope.launch {
                            busy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；无法恢复反诈设置")
                            } else {
                                val dryRun = settings.dryRun
                                val results = AntiFraudController(
                                    backend,
                                    dryRun
                                ).restoreAll()
                                antiFraudResults = results
                                if (!dryRun) {
                                    antiFraudConfig = AntiFraudConfig()
                                    antiFraudSettings.reset()
                                }
                                val text = "恢复：" +
                                    "${AntiFraudPolicy.summarize(results)}" +
                                    "（${System.currentTimeMillis()}）"
                                antiFraudLastResult = text
                                antiFraudSettings.lastResult = text
                                backendStatus = manager.status()
                            }
                            busy = false
                        }
                    }
```

- [ ] **Step 5: Build the app and run all unit tests**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`; all tests pass; APK at `D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`.

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/AndroidManifest.xml app/src/main/java/com/nemu/hibernate/ui/AntiFraudSection.kt app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt app/src/main/java/com/nemu/hibernate/MainActivity.kt
git commit -m "feat: add separate anti-fraud UI with confirmations and restore"
```

---

### Task 6: Documentation updates

**Files:**
- Modify: `D:\win_root\nemu\README.md`
- Modify: `D:\win_root\nemu\docs\03-design.md`
- Modify: `D:\win_root\nemu\docs\04-test-plan.md`
- Modify: `D:\win_root\nemu\docs\02-package-inventory.md`

**Interfaces:**
- Consumes: the implemented behavior from Tasks 1–5.
- Produces: truthful, non-contradictory documentation. Device-test results are not written until the user runs them; use `未测` until then.

- [ ] **Step 1: Update `README.md`**

Add to the `## 状态` list:

```markdown
- 2026-11-21：反诈模块（P3）代码完成，待实机验收。号码识别可选暂停/停用；安全公共服务与情景感知只限制后台/待机；与更新/AI 列表分开，默认关闭。
```

Add a new section after `## 安全解冻（始终开启）` and before `## 文档`:

```markdown
## 反诈与安全（高级，默认关闭）

- **号码识别** `com.hihonor.numberidentity`：三选一“不处理 / 暂停 / 停用（更强）”。暂停先 `pm enable` 再 `pm suspend`（清理旧的停用状态），停用是 `pm disable-user`；两者都会失去来电识别与骚扰/诈骗标记，停用更彻底。恢复命令为 `pm unsuspend` + `pm enable`。
- **安全公共服务** `com.hihonor.securityserver` 与 **情景感知** `com.hihonor.awareness`：**永不整包暂停/停用**。只提供 `appops set ... RUN_ANY_IN_BACKGROUND ignore` 与 `am set-standby-bucket ... restricted`，恢复为 `allow` / `active`。可能降低反诈告警、情景感知、人脸活体/深伪检测能力。
- **安全插件底座** `com.hihonor.securitypluginbase`：仅观察，不提供一键操作。
- 所有应用操作都需要确认对话框；号码识别“停用”还需勾选确认“失去来电识别/诈骗标记”。
- Dry run 同样作用于本模块：只记录命令，不执行。把号码识别切回“不处理”不会自动恢复；请用“恢复反诈设置”。
- 系统更新可能重新启用号码识别或重置 AppOps/standby；Nemu 保存的只是用户意图，更新后请重新检查并应用。
- 已知问题：部分机型 `securityserver`（libdexkit）存在开机崩溃，与本模块无关；不要把该崩溃归因于 Nemu。
```

Replace the existing non-goal line:

```markdown
- 不做卸载/停用系统应用（只 suspend）。
```

with:

```markdown
- 不做卸载（`pm uninstall`）。除运营商 DM 三件套与反诈模块的 `com.hihonor.numberidentity` 外，不整包停用系统应用；`com.hihonor.securityserver` 与 `com.hihonor.awareness` 永不整包暂停/停用。
```

Remove the anti-fraud item from the “后续可选” line so the status is not contradictory:

```markdown
- 后续可选：运营商云控、遥测、温控相关组件（默认关闭，需单独确认）。
```

- [ ] **Step 2: Update `docs/03-design.md`**

Replace the whole `### 7.2 反诈模块` section (table plus the paragraph after it) with:

```markdown
### 7.2 反诈模块（P3）

| 目标 | 模块动作 | 不提供 | 回滚 |
|---|---|---|---|
| `com.hihonor.numberidentity`（号码识别） | 三选一：不处理（默认）/ `suspend` / `disable-user` | `uninstall` | `pm unsuspend` + `pm enable` |
| `com.hihonor.securityserver`（安全公共服务） | 仅 `appops set ... RUN_ANY_IN_BACKGROUND ignore` + `am set-standby-bucket ... restricted` | **整包 suspend / disable-user** | AppOps `allow` + standby `active` |
| `com.hihonor.awareness`（情景感知） | 仅 AppOps + standby | **整包 suspend / disable-user** | AppOps `allow` + standby `active` |
| `com.hihonor.securitypluginbase` | 仅展示 | 任何一键操作 | 无（未修改） |

**实现约束**

- `numberidentity` 的 `disable-user` 是第二个明确例外（第一个是 §7.1 运营商 DM 三件套）：只允许在反诈模块中、只允许该包、必须经过二次确认（勾选“失去来电识别/诈骗标记”）。命令只由 `AntiFraudPolicy` 生成；通用 `FreezeController` 仍由 `DenyList` 拒绝整包冻结这四个包。
- `numberidentity` 的 `suspend` 动作先执行 `pm enable` 再执行 `pm suspend`，避免从 `disable-user` 直接切换到 `suspend` 时因包仍处于停用状态而静默无效。
- `securityserver` / `awareness` 的反诈控制器只能生成 AppOps/standby 命令。单元测试遍历 `NumberIdentityMode` × 两个布尔开关的全部组合，断言这两个包不会收到 `pm suspend` / `pm disable-user`。
- `securitypluginbase` 只出现在只读列表，任何 apply/restore 计划都不包含它。
- 所有命令逐条记录结果（包、动作、退出码、输出摘要）；UI 显示最近 20 条结果。
- 恢复始终幂等执行：号码识别 `pm unsuspend` + `pm enable`；两个核心包 AppOps `allow` + standby `active`。恢复不依赖当前开关状态。
- Dry run 同样作用于反诈模块：只写 `DRY-RUN` 日志，不执行；界面标注 `DRY-RUN`。

**安全解冻集成**

- `SafetyUnfreezer` 在关机/重启安全解冻中始终一并 `unsuspend` `com.hihonor.numberidentity`。`pm unsuspend` 幂等，且可覆盖“先应用 suspend、后来把模式改成 OFF 但没恢复”的残留状态。
- `disable-user` 不会被 `pm unsuspend` 撤销；重启后仍保持停用，由用户“恢复反诈设置”或 `pm enable` 恢复。
- 开机后不自动重新应用反诈设置；与核心模块一致，由用户手动应用。

**风险**

- 失去来电识别、骚扰/诈骗号码标记；号码识别暂停/停用期间在线识别停止。`suspend` 不阻止已绑定/后台运行的系统服务，`disable-user` 更彻底。
- `securityserver` / `awareness` 的后台/待机限制可能降低反诈告警、情景感知、人脸活体/深伪检测能力；系统可能忽略对 uid=system 的限制。
- 系统更新可能重新启用 `numberidentity` 或重置 AppOps/standby；Nemu 保存的是用户意图，不保证实际状态。
- 部分机型 `securityserver`（libdexkit）存在开机崩溃的已知问题；这不是 Nemu 的 AppOps/standby 操作导致的，不要归因于 Nemu。
- `pm disable-user` 或 `am set-standby-bucket` 可能被系统拒绝；按命令结果显示失败，保留 `suspend` / AppOps 作为降级路径。
```

Update `§2 非目标` bullet:

```markdown
- 不做 `pm uninstall`。不做整包 `pm disable-user`，唯一例外是 §7.1 运营商 DM 三件套和 §7.2 的 `com.hihonor.numberidentity`（需二次确认）；`securityserver` / `awareness` 永不整包停用。
```

Update `§4.5` item 1:

```markdown
1. **Never uninstall；受控 disable**：代码中不存在 `pm uninstall` 调用路径。`pm disable-user` 只允许在 `AntiFraudPolicy` 中对 `com.hihonor.numberidentity` 生成；运营商 DM 三件套的 disable-user 由独立模块（尚未实现）负责。`securityserver` / `awareness` 永不整包停用。
```

Add to `§4.4` after item 2:

```markdown
   反诈模块的 `com.hihonor.numberidentity` 也始终包含在安全解冻列表中（`pm unsuspend` 幂等；`disable-user` 不会被它撤销）。
```

Update `§4.5` item 4:

```markdown
4. **Restore all（核心）**：一键对所有核心目标 `unsuspend`；如检测到核心目标被外部工具 disable，提示用户手动 `pm enable`（核心模块不自动执行）。反诈模块的“恢复反诈设置”会对 `numberidentity` 显式执行 `pm enable`（见 §7.2）。
```

Update `§4.5` item 5 (after the existing safety paragraph) by adding:

```markdown
   反诈模块的 `numberidentity` 始终纳入关机/开机安全解冻（`pm unsuspend` 幂等）；`disable-user` 不会被 `pm unsuspend` 撤销。
```

Update `§4.5` item 7:

```markdown
7. **确认**：反诈模块已实现逐次确认与 `disable-user` 复选框；核心目标首次启用、切换后端的二次确认仍推迟。
```

Update `§4.6` by adding a bullet after the 首页 bullet:

```markdown
- **反诈区块（已实现）**：独立于更新/AI 列表；号码识别三选一（不处理/暂停/停用）；securityserver/awareness 仅后台/待机限制；逐次确认、逐条结果、一键恢复。
```

Update `§7.4` table row P3:

```markdown
| P3 | 反诈模块（`numberidentity` 可逆暂停/停用 + `securityserver`/`awareness` AppOps/standby） | 代码完成；待实机验收。运营商 DM/遥测未开始 |
```

Update `§7.4` “Core 实现范围” bullets:

```markdown
- 已实现：Root/Shizuku 自动选择、suspend/unsuspend/force-stop/clear-cache、AI 叶子包、Dry run、禁止清单、Restore all、关机/重启安全解冻（前台服务 + 运行时广播）、best-effort 开机安全解冻、反诈模块（默认关闭）。
- 推迟：手动后端切换 UI、状态回读、日志导出、运营商 DM/遥测、COTA/温控。
```

- [ ] **Step 3: Update `docs/04-test-plan.md`**

Add a new section after §7 (实测记录), titled `## 8. 反诈模块测试（P3，待用户执行）`, with exactly these test cases:

````markdown
## 8. 反诈模块测试（P3，待用户执行）

> 助手只写代码/文档；安装、点击和所有 `adb`/`pm`/`appops` 写入均由用户执行。
> 一次只测一个动作；每次先记录基线，再操作，再只读验证，最后恢复。

### AF1 号码识别 `suspend`

基线：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

步骤：

1. 打开 Nemu，反诈区块选择“号码识别 → 暂停”，关闭 Dry run，点“应用反诈设置”，在确认对话框点“确认执行”。
2. 只读验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=true`、`enabled=1`。

3. 用一个未知/骚扰号码来电，观察是否还显示号码识别/诈骗标记；记录“失去”或“仍显示”。
4. 点“恢复反诈设置”并确认，再次验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=false`、`enabled=1`。

通过标准：apply 的 `pm enable` + `pm suspend` 与 restore 的 `pm unsuspend` + `pm enable` 均成功；状态与期望一致；手机无异常重启。

### AF2 号码识别 `disable-user`

基线同 AF1。步骤：

1. 反诈区块选择“号码识别 → 停用（更强）”，关闭 Dry run，点“应用反诈设置”。
2. 确认对话框必须出现“我确认停用号码识别，并接受失去来电识别/诈骗标记”复选框；未勾选时“确认执行”不可用。
3. 勾选并执行，验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
adb shell pm list packages -d | Select-String 'com.hihonor.numberidentity'
```

期望：`enabled=0`；`pm list packages -d` 包含该包。不同构建可能显示 `enabled=0` 或 `enabled=3`（disabled-user）；以 `pm list packages -d` 包含该包为准。

4. 点“恢复反诈设置”，验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
adb shell pm list packages -d | Select-String 'com.hihonor.numberidentity'
```

期望：`enabled=1`；`pm list packages -d` 不再包含该包。

如果系统拒绝 `pm disable-user`，记录原始输出；AF1 的 `suspend` 是降级路径。

### AF3 安全公共服务后台/待机限制

基线：

```powershell
adb shell appops get --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.securityserver
```

步骤：

1. 反诈区块打开“安全公共服务”限制开关，关闭 Dry run，应用并确认。
2. 验证：

```powershell
adb shell appops get --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.securityserver
```

期望：AppOps 输出 `RUN_ANY_IN_BACKGROUND: ignore`；bucket 为 `restricted`（不同构建可能打印数值 `45`）。

如果 `am set-standby-bucket` 对 uid=system 包返回错误或被忽略，记录原始输出，标记“standby 平台受限”，AppOps 仍是主限制。

3. 观察电话/短信/系统管家是否正常；记录反诈提示是否减少（这是预期副作用，不是故障）。
4. 恢复并验证：AppOps `allow`；bucket `active`（或数值 `10`）。

### AF4 情景感知后台/待机限制

基线：

```powershell
adb shell appops get --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.awareness
```

步骤与 AF3 相同，包名换成 `com.hihonor.awareness`。额外观察：相机人脸解锁/活体检测是否可用（如果设备使用）。记录副作用；不把 `securityserver` 的 libdexkit 开机崩溃归因于本模块。

### AF5 重启与安全解冻

1. 号码识别选“暂停”并应用；确认 `suspended=true`。
2. 正常重启（`adb shell svc power reboot` 或电源菜单），不要用 `adb reboot`。
3. 开机后验证：

```powershell
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
```

期望：`suspended=false`（关机安全解冻已包含 numberidentity）；Nemu 中反诈设置仍显示“暂停”，但不会自动重新应用。

4. 号码识别选“停用”并应用；正常重启；开机后验证 `enabled=0`（安全解冻不会 `pm enable`）。然后恢复。

5. 可选但推荐：应用 `suspend` 后，把号码识别切回“不处理”并应用（不点“恢复反诈设置”），再正常重启；开机后验证 `suspended=false`。这证明安全解冻不依赖保存的开关状态。

### AF6 系统更新后状态检查（观察项）

系统更新后重新执行 AF1/AF3/AF4 的基线命令，记录号码识别是否被重新启用、AppOps/standby 是否被重置。Nemu 的 `antifraud_*` 设置会保留，但不代表系统实际状态；需要时重新应用。
````

Add to §3 验收标准 (unchecked until user runs):

```markdown
- [ ] AF1 号码识别 suspend + 恢复通过。
- [ ] AF2 号码识别 disable-user + 恢复通过（若平台拒绝，记录并接受 suspend 为降级）。
- [ ] AF3/AF4 两个核心包 AppOps/standby 限制与恢复通过（standby 可为平台受限）。
- [ ] AF5 重启安全解冻行为符合预期。
- [ ] 反诈 UI 与更新/AI 列表分离，确认对话框和 disable-user 复选框生效。
```

Update §3 existing line:

```markdown
- [x] 代码中不存在 `pm uninstall` / `pm disable-user` 调用路径。
```

to:

```markdown
- [x] 代码中不存在 `pm uninstall` 调用路径；`pm disable-user` 只由 `AntiFraudPolicy` 对 `com.hihonor.numberidentity` 生成。
```

Add to §4 风险登记:

```markdown
| 反诈能力下降 | 高 | 高（应用后） | 默认关闭；确认对话框；逐项恢复；文档说明 |
| 系统更新重置号码识别/AppOps/standby | 中 | 中 | 更新后按 §8 AF6 重新检查并应用 |
| `securityserver` libdexkit 开机崩溃 | 高 | 低（平台已知问题） | 不归因于 Nemu；按 §5 恢复；必要时系统恢复 |
| `pm disable-user` 被系统拒绝 | 低 | 中 | 保留 suspend 降级路径；按命令结果显示失败 |
| standby bucket 对 uid=system 无效 | 低 | 中 | AppOps 为主；记录平台限制 |
```

Add to §5 回滚命令:

```powershell
# 反诈模块回滚（号码识别）
adb shell pm unsuspend --user 0 com.hihonor.numberidentity
adb shell pm enable --user 0 com.hihonor.numberidentity

# 反诈模块回滚（安全公共服务 / 情景感知，仅 AppOps/standby）
adb shell appops set --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket --user 0 com.hihonor.securityserver active
adb shell appops set --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket --user 0 com.hihonor.awareness active
```

In §7.1, replace the existing C5 row with the first row below, replace the existing C9 row with the second, and add the new C11 row after them:

```markdown
| 2026-11-21 | C5 `pm disable-user` 仅限反诈号码识别 | 通过（代码级） | `AntiFraudPolicy` 只对 `com.hihonor.numberidentity` 生成；无其他生产调用点 |
| 2026-11-21 | C9 冻结命令面 | 通过（代码级） | `FreezeController.apply` 只生成 `pm suspend` / `am force-stop` / `pm clear --cache-only`；`restore` 只生成 `pm unsuspend`。`AntiFraudPolicy` 另行生成受控的 `pm disable-user` / `pm enable` / AppOps / standby，仅用于反诈模块；`pm uninstall` 无调用点 |
| 2026-11-21 | C11 反诈动作矩阵测试 | 通过（代码级） | `AntiFraudPolicyTest` 遍历全部配置，断言 securityserver/awareness 无整包动作；`securitypluginbase` 无命令 |
```

- [ ] **Step 4: Update `docs/02-package-inventory.md` §3**

Replace the four rows with:

```markdown
| `com.hihonor.securityserver` | **安全公共服务** | 12.1.0.1 | 反诈核心：采集/上报通话、短信、位置、应用列表等；uid=system | **红** | **不整包停用**；反诈模块仅 AppOps `RUN_ANY_IN_BACKGROUND ignore` + standby `restricted` |
| `com.hihonor.awareness` | **情景感知** | 10.0.0.375 | 41 处 fraud 逻辑 + 独立上报；人脸活体/深伪载体 | **红** | **不整包停用**；反诈模块仅 AppOps + standby |
| `com.hihonor.numberidentity` | **号码识别** | 18.0.10.305 | 在线识别骚扰/诈骗号码；上传未知来电去电号码 | 黄 | 反诈模块可选 `suspend`（默认不处理）或 `disable-user`（需二次确认）；可逆 |
| `com.hihonor.securitypluginbase` | HnSecurityPluginBase | 16.0.0.101 | 安全插件底座（疑似） | 黄 | 仅观察，不提供一键操作 |
```

Add after the existing evidence list:

```markdown
- 反诈模块的代码约束：`securityserver` / `awareness` 只允许 AppOps/standby，永不整包 suspend/disable-user；`numberidentity` 的 disable-user 是明确例外，需二次确认；`securitypluginbase` 不生成任何命令。
- 已知平台问题：部分机型 `securityserver`（libdexkit）存在开机崩溃，与本项目的 AppOps/standby 操作无关，不要归因于 Nemu。
```

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add README.md docs/03-design.md docs/04-test-plan.md docs/02-package-inventory.md
git commit -m "docs: document anti-fraud module, guardrails, and device tests"
```

---

### Task 7: Final verification and acceptance handoff

**Files:**
- No source changes. Read-only verification.

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces: a buildable APK, passing unit tests, and a truthful handoff for user-executed device tests.

- [ ] **Step 1: Run the full unit test suite and build**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; all tests pass; APK at `D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`.

- [ ] **Step 2: Run the constraint greps**

```powershell
cd D:\win_root\nemu
rg -ni "pm uninstall" app/src
rg -ni "hail" app/src build.gradle.kts settings.gradle.kts gradle.properties
rg -n "disableUser" app/src/main/java
```

Expected:

- No `pm uninstall` matches.
- No `hail` matches.
- `disableUser` appears in `app/src/main/java` only in `CommandBuilder.kt` (definition) and `AntiFraudPolicy.kt` (numberidentity call). The test source `CommandBuilderTest.kt` also contains the string assertion.

- [ ] **Step 3: Confirm the policy guard by test name**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.AntiFraudPolicyTest" --tests "com.nemu.hibernate.policy.AntiFraudControllerTest"
```

Expected: all policy/controller tests pass, including `noConfig_everPlansWholePackageActionForCoreSecurityPackages`, `securityPluginBaseIsNeverTouched`, `execute_rejectsWholePackageDisableForSecurityServer`, and `execute_rejectsSecurityPluginBase`.

- [ ] **Step 4: Record the APK identity**

```powershell
Get-FileHash D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Record size and SHA-256 in `docs/04-test-plan.md` §7.1 when the user builds the acceptance APK.

- [ ] **Step 5: Hand off device tests**

Tell the user:

- Install with `adb install -r D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`.
- Run AF1–AF5 from `docs/04-test-plan.md` §8 in order. Do not batch-test.
- The assistant does not run any `adb`/`pm`/`appops` write command and does not write to the phone.

- [ ] **Step 6: Commit any recorded results**

```powershell
cd D:\win_root\nemu
git add README.md docs/03-design.md docs/04-test-plan.md docs/02-package-inventory.md
git commit -m "docs: record anti-fraud acceptance results"
```

Only commit after the user provides actual device results. Add one row per executed test to `docs/04-test-plan.md` §7.2 with date, test ID, result (`通过` / `失败` / `未测`), and notes. Unrun tests stay `未测`.

---

## Manual Device Test Checklist (user-executed)

| ID | Test | Expected |
|---|---|---|
| AF1 | `numberidentity` suspend + restore | `suspended=true` → `suspended=false`; caller ID/spam marking lost while suspended |
| AF2 | `numberidentity` disable-user + restore | `enabled=0` and listed in `pm list packages -d` → `enabled=1`; checkbox required |
| AF3 | `securityserver` AppOps/standby | AppOps `ignore`, bucket `restricted` → `allow` / `active`; no crash |
| AF4 | `awareness` AppOps/standby | Same as AF3; note face-liveness side effects |
| AF5 | Reboot safety | Suspended numberidentity becomes `suspended=false` after normal reboot; disabled stays disabled |
| AF6 | Post-system-update check | Record whether OS re-enabled numberidentity or reset AppOps/standby |

## Acceptance Criteria

**Code-level (assistant-verifiable):**

- [ ] `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks` succeeds.
- [ ] `AntiFraudPolicyTest` passes, including exhaustive configuration guard.
- [ ] `AntiFraudControllerTest` passes, including Dry-run, per-command failure, restore, and forbidden-action rejection.
- [ ] `securityserver` / `awareness` never receive `pm suspend` or `pm disable-user` in any generated plan.
- [ ] `securitypluginbase` receives no command in apply or restore.
- [ ] `pm disable-user` has exactly one production call site: `AntiFraudPolicy` for `com.hihonor.numberidentity`.
- [ ] No `pm uninstall` and no Hail references.
- [ ] Anti-fraud UI is a separate section; apply has a confirmation dialog; `disable-user` requires the acknowledgement checkbox; restore has its own confirmation.
- [ ] Anti-fraud settings use `antifraud_*` keys and do not collide with `target_<pkg>`.
- [ ] `SafetyUnfreezer` includes `numberidentity` unconditionally; `pm unsuspend` is idempotent and does not re-enable a `disable-user` package.

**Device-level (user-executed, recorded in `docs/04-test-plan.md` §7/§8):**

- [ ] AF1–AF5 executed in order; each result recorded as `通过`, `失败`, or `未测` with exact `dumpsys`/`appops` output.
- [ ] If `am set-standby-bucket` is refused for uid=system packages, record the exact error and mark standby as platform-limited; AppOps must still be verified.
- [ ] Restore returns `suspended=false` / `enabled=1` for numberidentity and `allow` / `active` for the two core packages.
- [ ] No bootloop or system crash attributable to Nemu. A `securityserver` libdexkit crash is recorded as a pre-existing platform issue, not a Nemu regression.

## Rollback

In-app: anti-fraud section → “恢复反诈设置” → “确认恢复”.

Manual commands (user-executed):

```powershell
# 号码识别
adb shell pm unsuspend --user 0 com.hihonor.numberidentity
adb shell pm enable --user 0 com.hihonor.numberidentity

# 安全公共服务
adb shell appops set --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket --user 0 com.hihonor.securityserver active

# 情景感知
adb shell appops set --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket --user 0 com.hihonor.awareness active

# 状态检查
adb shell dumpsys package com.hihonor.numberidentity | Select-String 'User 0:'
adb shell appops get --user 0 com.hihonor.securityserver RUN_ANY_IN_BACKGROUND
adb shell appops get --user 0 com.hihonor.awareness RUN_ANY_IN_BACKGROUND
adb shell am get-standby-bucket --user 0 com.hihonor.securityserver
adb shell am get-standby-bucket --user 0 com.hihonor.awareness
```

If ADB is unavailable, use a Shizuku-authorized terminal or the KSU manager terminal with the same commands. Do not use `pm uninstall`.

## Risks

| Risk | Impact | Probability | Mitigation |
|---|---|---|---|
| Loss of caller ID / spam marking while `numberidentity` is paused or disabled | Medium | Certain when applied | Default OFF; confirmation; restore button; docs |
| Anti-fraud / security / face-liveness degradation from `securityserver` / `awareness` restrictions | High | High when applied | Default OFF; explicit warnings; only AppOps/standby, never whole-package; restore |
| AppOps/standby reset or `numberidentity` re-enabled after system update | Medium | Medium | Document; AF6 post-update check; reapply manually |
| `securityserver` libdexkit boot crash on some devices | High | Low, platform-known | Do not attribute to Nemu; manual rollback; document in README/design/test plan |
| `pm disable-user` refused for a system package | Low | Medium | Per-action failure result; `suspend` remains the fallback |
| `am set-standby-bucket` refused/ignored for uid=system packages | Low | Medium | AppOps is the primary restriction; record platform limitation |
| Shizuku/root unavailable | Medium | Medium | Buttons report “无可用权限”; no partial writes |
| User switches numberidentity to “不处理” and expects automatic restore | Medium | Medium | UI text: use “恢复反诈设置”; restore is separate and explicit |
| Dry-run saved intent is mistaken for actual state | Low | Medium | `DRY-RUN` labels in per-action results and summary; docs |
| `securitypluginbase` dependency breakage | Medium | Low | Observe-only; no commands generated |
| Suspended `numberidentity` carried across reboot | Medium | Low | Included unconditionally in `SafetyUnfreezer`; `pm unsuspend` is idempotent; AF5 verifies |

## Decisions (no open placeholders)

1. `numberidentity` defaults to `OFF`; `suspend` and `disable-user` are opt-in.
2. `disable-user` requires an explicit checkbox acknowledgement every time it is applied.
3. Restore is always idempotent and runs all six restore commands, regardless of the saved config.
4. Dry run is respected by both apply and restore. Apply still saves the intent config; restore under Dry run does not reset the saved config.
5. `numberidentity` is included in shutdown/boot safety unfreeze unconditionally; `pm unsuspend` is idempotent and does not undo `disable-user`.
6. `securitypluginbase` has no action and no restore command.
7. No auto re-apply after reboot or system update.
