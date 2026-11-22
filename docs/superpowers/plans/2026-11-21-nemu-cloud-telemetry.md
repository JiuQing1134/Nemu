# Nemu Cloud/Telemetry Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a separate, default-off cloud/telemetry module to Nemu: reversible `pm disable-user` for the carrier DM trio, reversible `pm suspend` for the hiview trio, observe-only status for `cipservice`, per-target confirmation, live read-only package status, and a one-tap "恢复全部云控" rollback.

**Architecture:** The cloud module is deliberately separate from the core freeze list. New pure-Kotlin types (`CloudAction`, `CloudTarget`, `CloudTargets`, `CloudStatus`) are JVM-tested. `CloudController` turns enabled `CloudTarget`s into guarded shell commands and uses the existing `PrivilegeBackend`; `CloudSettings` stores cloud-only keys with a `cloud_` prefix in the same `nemu` SharedPreferences file, so it cannot collide with the core `target_*` keys. `CloudSection` is a distinct Compose section below the update/AI list. Cloud suspend targets are added to `SafetyTargets`, so the existing shutdown/boot safety path also unsuspends them.

**Tech Stack:** Kotlin 2.4.20, AGP 9.1.0, Compose BOM 2025.12.01, Material3, Shizuku API/provider 13.1.5, JUnit 4.13.2. No new dependencies.

**Spec:** `../../03-design.md` §7.1 (cloud/telemetry decisions), `../../02-package-inventory.md` §4 (package evidence), `../../04-test-plan.md` (device test format). Project constraints: root `D:\win_root\nemu`, branch `main`, current commit `fdc3ab1`.

## Global Constraints

- **No `pm uninstall` anywhere.** It must not appear in source, tests, or UI copy.
- **In this module, `pm disable-user` is allowed only for the carrier DM trio** (`com.hihonor.regservice`, `com.hihonor.android.UEInfoCheck`, `com.hihonor.android.AutoRegSms`). The controller enforces a second, code-level allowlist (`CloudTargets.disableUserAllowed`) in addition to the UI confirmation. Other modules may add their own documented exception, but they must not reuse this controller's allowlist.
- **Hiview trio uses `suspend`, not `disable-user`.** The disable-user exception stays narrow; hiview is reversible with `pm unsuspend`.
- **Cloud targets are default off.** No cloud package may be acted on unless `CloudSettings.isEnabled(pkg)` is true.
- **Per-target confirmation is required before any disable-user command.** `CloudSettings.isConfirmed(pkg)` must be true; toggling off clears it; toggling on again shows the dialog again.
- **No Hail dependency/API/Intent/data.** The word "Hail" must not appear in new source or build files.
- **Do not modify core behavior.** `FreezeController`, `Targets`, `DenyList`, `Settings`, and `CommandBuilder` keep their existing semantics. The only core-adjacent change is extending the safety unfreeze package set.
- **No live write by the implementer.** This plan may be executed by an agent for code, tests, and docs, but every `adb install`, `adb shell pm ...`, reboot, or device write is executed by the user. The device test section is a user-executed checklist.
- **No false promises in UI.** Package status shown in the app comes from real `PackageManager` state. The app must not claim to block cloud traffic, guarantee FOTA behavior, or read state it does not read.
- **UI strings Chinese, code identifiers English.**

## Scope

### In scope

1. Carrier DM trio: `com.hihonor.regservice`, `com.hihonor.android.UEInfoCheck`, `com.hihonor.android.AutoRegSms` -> per-target confirmed `pm disable-user --user 0`, rollback `pm enable --user 0`.
2. Telemetry trio: `com.hihonor.hiview`, `com.hihonor.hiviewpush`, `com.hihonor.hiviewtunnel` -> `pm suspend --user 0` + `am force-stop`, rollback `pm unsuspend --user 0`; default off.
3. Observe-only `com.hihonor.cipservice`: status display only, no action.
4. Exclude `com.hihonor.android.pushagent` (already in `DenyList`) and all core services.
5. Separate cloud/telemetry UI section with explicit warning, per-target toggles, confirmation dialog, live status, apply, refresh, and "恢复全部云控".
6. Cloud-only settings with a `cloud_` key prefix; shared `dry_run` with the core module.
7. JVM unit tests for targets, guards, command construction, rollback, dry-run, and safety package composition.
8. Documentation updates in `README.md`, `docs/03-design.md`, `docs/02-package-inventory.md`, `docs/04-test-plan.md`.

### Out of scope

- Anti-fraud module (`numberidentity`, AppOps/standby for `securityserver`/`awareness`).
- COTA/thermal file operations.
- Uninstall or `pm uninstall --user`.
- Per-target restore buttons (the required rollback is the global "恢复全部云控"; toggles only add/remove policy).
- Live `dumpsys` parsing via the privilege backend. Status uses the public `PackageManager` APIs.
- Work-profile / secondary-user handling. `--user 0` only.

## Design Decisions That Must Not Drift

1. **Status is real, not simulated.** `AndroidPackageStatusReader` calls `PackageManager.getApplicationEnabledSetting(pkg)` and `PackageManager.isPackageSuspended(pkg)` (both verified present in `android-37.2/android.jar`). If either throws, status is "未知（读取失败或未安装）".
2. **Cloud policy state and actual package state are shown separately.** "策略：已纳入/未纳入" is the stored toggle; "状态：已启用/已停用/已暂停" is the OS readback. The UI never conflates them.
3. **Rollback ignores enabled toggles.** `restoreAll` runs for every non-observe cloud target, so it still recovers after app data is cleared or a toggle was switched off. It does not clear the toggles.
4. **Cloud suspend targets join the safety unfreeze set.** `SafetyTargets.suspendPackageNames = core targets + hiview trio`. A normal shutdown/boot therefore unsuspends them, preserving the project rule "never carry suspend across a normal reboot". `adb reboot` and crashes can still skip this, as documented for the core module.
5. **`disable-user` persistence is intentional.** The trio stays disabled across reboots until "恢复全部云控" or a manual `pm enable`. This is the point of the carrier-DM module and is different from the suspend safety rule.
6. **No `force-stop` or `clear --cache-only` for disable-user.** `pm disable-user` kills the package processes; one command is enough and makes the device test easy to reason about.

## File Structure

New files:

```text
D:\win_root\nemu\
  app/src/main/java/com/nemu/hibernate/
    core/CloudTarget.kt
    core/CloudTargets.kt
    core/CloudStatus.kt
    data/CloudSettingsStore.kt
    data/CloudSettings.kt
    policy/CloudController.kt
    policy/SafetyTargets.kt
    privilege/PackageStatusReader.kt
    ui/CloudSection.kt
  app/src/test/java/com/nemu/hibernate/
    core/CloudTargetsTest.kt
    core/CloudStatusTest.kt
    policy/CloudControllerTest.kt
    policy/SafetyTargetsTest.kt
```

Modified files:

```text
D:\win_root\nemu\
  app/src/main/java/com/nemu/hibernate/
    policy/SafetyUnfreezer.kt        (add package-list overload)
    policy/ShutdownSafety.kt         (use SafetyTargets)
    policy/BootWorker.kt             (use SafetyTargets)
    ui/MainScreen.kt                 (new cloud section + updated warning)
    MainActivity.kt                  (wire cloud state, controller, status reader)
  app/src/main/AndroidManifest.xml   (add cloud package queries)
  app/src/test/java/com/nemu/hibernate/policy/SafetyUnfreezerTest.kt (one new test)
  README.md
  docs/03-design.md
  docs/02-package-inventory.md
  docs/04-test-plan.md
```

Files deliberately untouched: `core/CommandBuilder.kt` (`disableUser`/`enable` already exist), `core/Targets.kt`, `core/DenyList.kt`, `core/Target.kt`, `data/Settings.kt`, `policy/FreezeController.kt`.

**Cross-plan integration:** the anti-fraud and COTA plans in this directory also modify `MainActivity.kt`, `MainScreen.kt`, `AndroidManifest.xml`, and the safety files. `docs/06-module-integration-plan.md` additionally asks that shared files (`MainScreen.kt`, `MainActivity.kt`) be changed once by a single integration task. If that process is used, implement the module's new files first (Tasks 1-3 and Task 4 Steps 1-2), then let the integration task perform Task 4 Steps 3-5 (shared UI files and manifest) using the `CloudUiState`/`CloudSection` contract defined here. If the other module plans are already implemented, rebase instead of overwriting: (a) append the cloud section next to the other module sections; (b) extend the existing safety package-set helper to a union rather than creating a second `SafetyTargets`; (c) keep the cloud test IDs `CT1–CT5` (the other plans use `T11+`).

## Interfaces

```kotlin
// core/CloudTarget.kt
enum class CloudAction { DISABLE_USER, SUSPEND, OBSERVE_ONLY }
data class CloudTarget(
    val packageName: String,
    val label: String,
    val action: CloudAction,
    val description: String
)

// core/CloudTargets.kt
object CloudTargets {
    val carrierDm: List<CloudTarget>
    val telemetry: List<CloudTarget>
    val observeOnly: List<CloudTarget>
    val all: List<CloudTarget>
    val disableUserAllowed: Set<String>
    val suspendPackageNames: List<String>
    fun byPackage(packageName: String): CloudTarget?
}

// core/CloudStatus.kt
data class CloudStatus(val enabled: Boolean, val suspended: Boolean) {
    fun describe(action: CloudAction): String
}

// data/CloudSettingsStore.kt (pure Kotlin, JVM-testable)
interface CloudSettingsStore {
    var dryRun: Boolean
    var lastResult: String?
    fun isEnabled(packageName: String): Boolean
    fun setEnabled(packageName: String, enabled: Boolean)
    fun isConfirmed(packageName: String): Boolean
    fun setConfirmed(packageName: String, confirmed: Boolean)
}

// data/CloudSettings.kt (Android SharedPreferences implementation)
class CloudSettings(context: Context) : CloudSettingsStore

// policy/CloudController.kt
class CloudController(backend: PrivilegeBackend, store: CloudSettingsStore) {
    suspend fun apply(targets: List<CloudTarget>): Boolean
    suspend fun restoreAll(targets: List<CloudTarget>): Boolean
}

// policy/SafetyTargets.kt
object SafetyTargets {
    val suspendPackageNames: List<String>
}

// policy/SafetyUnfreezer.kt (new overload; existing function delegates)
suspend fun unsuspendPackages(backend: PrivilegeBackend, packages: Collection<String>): Boolean

// privilege/PackageStatusReader.kt
interface PackageStatusReader { fun read(packageName: String): CloudStatus? }
class AndroidPackageStatusReader(context: Context) : PackageStatusReader

// ui/CloudSection.kt
data class CloudUiState(
    val targets: List<CloudTarget>,
    val enabled: Map<String, Boolean>,
    val statuses: Map<String, CloudStatus>,
    val lastResult: String,
    val busy: Boolean,
    val dryRun: Boolean
)
@Composable fun CloudSection(
    state: CloudUiState,
    onToggle: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onRestoreAll: () -> Unit,
    onRefreshStatus: () -> Unit
)
```

---

### Task 1: Cloud target model, target tables, and status text

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/core/CloudTarget.kt`
- Create: `app/src/main/java/com/nemu/hibernate/core/CloudTargets.kt`
- Create: `app/src/main/java/com/nemu/hibernate/core/CloudStatus.kt`
- Test: `app/src/test/java/com/nemu/hibernate/core/CloudTargetsTest.kt`
- Test: `app/src/test/java/com/nemu/hibernate/core/CloudStatusTest.kt`

**Interfaces:**
- Consumes: `DenyList` and `Targets` (existing, for tests only).
- Produces: `CloudAction`, `CloudTarget`, `CloudTargets` (`carrierDm`, `telemetry`, `observeOnly`, `all`, `disableUserAllowed`, `suspendPackageNames`, `byPackage`), `CloudStatus.describe(CloudAction)`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/nemu/hibernate/core/CloudTargetsTest.kt`:

```kotlin
package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTargetsTest {
    @Test
    fun carrierDmTrio_is_disable_user_and_configured() {
        assertEquals(
            setOf(
                "com.hihonor.regservice",
                "com.hihonor.android.UEInfoCheck",
                "com.hihonor.android.AutoRegSms"
            ),
            CloudTargets.carrierDm.map { it.packageName }.toSet()
        )
        CloudTargets.carrierDm.forEach {
            assertEquals(CloudAction.DISABLE_USER, it.action)
        }
    }

    @Test
    fun telemetryTrio_is_suspend_and_configured() {
        assertEquals(
            setOf(
                "com.hihonor.hiview",
                "com.hihonor.hiviewpush",
                "com.hihonor.hiviewtunnel"
            ),
            CloudTargets.telemetry.map { it.packageName }.toSet()
        )
        CloudTargets.telemetry.forEach {
            assertEquals(CloudAction.SUSPEND, it.action)
        }
    }

    @Test
    fun cipservice_is_observe_only() {
        val cip = CloudTargets.byPackage("com.hihonor.cipservice")
        assertEquals(CloudAction.OBSERVE_ONLY, cip!!.action)
    }

    @Test
    fun pushagent_is_not_a_cloud_target_and_is_protected() {
        assertNull(CloudTargets.byPackage("com.hihonor.android.pushagent"))
        assertTrue(DenyList.isProtected("com.hihonor.android.pushagent"))
    }

    @Test
    fun no_cloud_target_is_protected() {
        CloudTargets.all.forEach {
            assertFalse(it.packageName, DenyList.isProtected(it.packageName))
        }
    }

    @Test
    fun cloud_targets_do_not_overlap_core_targets() {
        val core = Targets.all.map { it.packageName }.toSet()
        CloudTargets.all.forEach {
            assertFalse(it.packageName, it.packageName in core)
        }
    }

    @Test
    fun disable_user_allowlist_is_exactly_the_carrier_trio() {
        assertEquals(
            setOf(
                "com.hihonor.regservice",
                "com.hihonor.android.UEInfoCheck",
                "com.hihonor.android.AutoRegSms"
            ),
            CloudTargets.disableUserAllowed
        )
    }

    @Test
    fun suspend_packages_are_exactly_the_telemetry_trio() {
        assertEquals(
            setOf(
                "com.hihonor.hiview",
                "com.hihonor.hiviewpush",
                "com.hihonor.hiviewtunnel"
            ),
            CloudTargets.suspendPackageNames.toSet()
        )
    }

    @Test
    fun every_cloud_package_is_unique() {
        val names = CloudTargets.all.map { it.packageName }
        assertEquals(names.size, names.distinct().size)
    }
}
```

`app/src/test/java/com/nemu/hibernate/core/CloudStatusTest.kt`:

```kotlin
package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudStatusTest {
    @Test
    fun disable_user_status_uses_enabled_flag() {
        assertEquals(
            "已启用",
            CloudStatus(enabled = true, suspended = false).describe(CloudAction.DISABLE_USER)
        )
        assertEquals(
            "已停用",
            CloudStatus(enabled = false, suspended = false).describe(CloudAction.DISABLE_USER)
        )
    }

    @Test
    fun suspend_status_uses_both_flags() {
        assertEquals("未暂停", CloudStatus(true, false).describe(CloudAction.SUSPEND))
        assertEquals("已暂停", CloudStatus(true, true).describe(CloudAction.SUSPEND))
        assertEquals("已暂停，已停用", CloudStatus(false, true).describe(CloudAction.SUSPEND))
    }

    @Test
    fun observe_only_uses_suspend_text() {
        assertEquals("未暂停", CloudStatus(true, false).describe(CloudAction.OBSERVE_ONLY))
    }

    @Test
    fun disabled_and_not_suspended_reads_clearly() {
        assertEquals("未暂停，已停用", CloudStatus(false, false).describe(CloudAction.SUSPEND))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.CloudTargetsTest" --tests "com.nemu.hibernate.core.CloudStatusTest"
```

Expected: compilation fails because `CloudAction`, `CloudTarget`, `CloudTargets`, and `CloudStatus` do not exist.

- [ ] **Step 3: Implement the model**

`app/src/main/java/com/nemu/hibernate/core/CloudTarget.kt`:

```kotlin
package com.nemu.hibernate.core

enum class CloudAction {
    DISABLE_USER,
    SUSPEND,
    OBSERVE_ONLY
}

data class CloudTarget(
    val packageName: String,
    val label: String,
    val action: CloudAction,
    val description: String
)
```

`app/src/main/java/com/nemu/hibernate/core/CloudTargets.kt`:

```kotlin
package com.nemu.hibernate.core

/**
 * Static cloud/telemetry targets. This list is deliberately separate from
 * [Targets] so the core freeze flow never suspends a disable-user package and
 * the cloud flow never touches the update/AI targets.
 */
object CloudTargets {
    val carrierDm: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.regservice",
            label = "系统消息（实际：中国移动终端管理 DM）",
            action = CloudAction.DISABLE_USER,
            description = "CMCC DM：自注册、心跳、FOTA、应用清单上报。停用为可逆操作。"
        ),
        CloudTarget(
            packageName = "com.hihonor.android.UEInfoCheck",
            label = "UEInfoCheck（电信数据上报）",
            action = CloudAction.DISABLE_USER,
            description = "电信 UE 信息检查/上报组件。停用为可逆操作。"
        ),
        CloudTarget(
            packageName = "com.hihonor.android.AutoRegSms",
            label = "AutoRegSms（电信自注册短信）",
            action = CloudAction.DISABLE_USER,
            description = "电信自注册短信组件。停用后可能收不到运营商自注册短信。"
        )
    )

    val telemetry: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.hiview",
            label = "hiview（遥测采集）",
            action = CloudAction.SUSPEND,
            description = "系统诊断/遥测采集；suspend 不阻止后台，配合 force-stop。"
        ),
        CloudTarget(
            packageName = "com.hihonor.hiviewpush",
            label = "hiviewpush（遥测推送）",
            action = CloudAction.SUSPEND,
            description = "遥测推送通道；suspend 不阻止后台，配合 force-stop。"
        ),
        CloudTarget(
            packageName = "com.hihonor.hiviewtunnel",
            label = "HiViewTunnel（遥测上传通道）",
            action = CloudAction.SUSPEND,
            description = "遥测上传通道；suspend 不阻止后台，配合 force-stop。"
        )
    )

    val observeOnly: List<CloudTarget> = listOf(
        CloudTarget(
            packageName = "com.hihonor.cipservice",
            label = "HnCIPService（仅观察）",
            action = CloudAction.OBSERVE_ONLY,
            description = "疑似 CHR 上报；本版本只展示状态，不提供操作。"
        )
    )

    val all: List<CloudTarget> = carrierDm + telemetry + observeOnly

    /** The only packages for which `pm disable-user` is allowed. */
    val disableUserAllowed: Set<String> = carrierDm.map { it.packageName }.toSet()

    /** Packages Nemu may leave suspended and must unsuspend before shutdown/boot. */
    val suspendPackageNames: List<String> = telemetry
        .filter { it.action == CloudAction.SUSPEND }
        .map { it.packageName }

    fun byPackage(packageName: String): CloudTarget? =
        all.firstOrNull { it.packageName == packageName }
}
```

`app/src/main/java/com/nemu/hibernate/core/CloudStatus.kt`:

```kotlin
package com.nemu.hibernate.core

/**
 * Read-only package state used by the cloud/telemetry UI. It comes from
 * PackageManager; it is not a promise that no background work remains.
 */
data class CloudStatus(
    val enabled: Boolean,
    val suspended: Boolean
) {
    fun describe(action: CloudAction): String = when (action) {
        CloudAction.DISABLE_USER -> if (enabled) "已启用" else "已停用"
        CloudAction.SUSPEND, CloudAction.OBSERVE_ONLY -> buildString {
            append(if (suspended) "已暂停" else "未暂停")
            if (!enabled) append("，已停用")
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.CloudTargetsTest" --tests "com.nemu.hibernate.core.CloudStatusTest"
```

Expected: both test classes pass, 13 tests total.

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/core/CloudTarget.kt app/src/main/java/com/nemu/hibernate/core/CloudTargets.kt app/src/main/java/com/nemu/hibernate/core/CloudStatus.kt app/src/test/java/com/nemu/hibernate/core/CloudTargetsTest.kt app/src/test/java/com/nemu/hibernate/core/CloudStatusTest.kt
git commit -m "feat: add cloud/telemetry target model"
```

---

### Task 2: Cloud settings and guarded controller

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/data/CloudSettingsStore.kt`
- Create: `app/src/main/java/com/nemu/hibernate/data/CloudSettings.kt`
- Create: `app/src/main/java/com/nemu/hibernate/policy/CloudController.kt`
- Test: `app/src/test/java/com/nemu/hibernate/policy/CloudControllerTest.kt`

**Interfaces:**
- Consumes: `CloudTarget`, `CloudAction`, `CloudTargets`, `CommandBuilder.disableUser/enable/suspend/forceStop/unsuspend`, `DenyList`, `ActionLog`, `PrivilegeBackend`.
- Produces: `CloudSettingsStore`, `CloudSettings`, `CloudController.apply(targets): Boolean`, `CloudController.restoreAll(targets): Boolean`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/nemu/hibernate/policy/CloudControllerTest.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.data.CloudSettingsStore
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudControllerTest {
    private class FakeBackend(
        private val results: MutableList<ShellResult> = mutableListOf()
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        val commands = mutableListOf<String>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return if (results.isEmpty()) ShellResult(0, "") else results.removeAt(0)
        }
    }

    private class FakeStore(
        enabled: Set<String> = emptySet(),
        confirmed: Set<String> = emptySet(),
        override var dryRun: Boolean = false
    ) : CloudSettingsStore {
        override var lastResult: String? = null
        private val enabledSet = enabled.toMutableSet()
        private val confirmedSet = confirmed.toMutableSet()

        override fun isEnabled(packageName: String): Boolean =
            packageName in enabledSet

        override fun setEnabled(packageName: String, enabled: Boolean) {
            if (enabled) enabledSet.add(packageName) else enabledSet.remove(packageName)
        }

        override fun isConfirmed(packageName: String): Boolean =
            packageName in confirmedSet

        override fun setConfirmed(packageName: String, confirmed: Boolean) {
            if (confirmed) confirmedSet.add(packageName) else confirmedSet.remove(packageName)
        }
    }

    private val regservice = "com.hihonor.regservice"
    private val hiview = "com.hihonor.hiview"

    @Test
    fun apply_skips_targets_that_are_not_enabled() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).apply(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("没有启用的目标"))
    }

    @Test
    fun apply_disable_user_requires_confirmation() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(regservice))

        val ok = CloudController(backend, store).apply(CloudTargets.carrierDm)

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("有失败或拒绝"))
    }

    @Test
    fun apply_disable_user_runs_after_confirmation() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            enabled = setOf(regservice),
            confirmed = setOf(regservice)
        )

        val ok = CloudController(backend, store).apply(CloudTargets.carrierDm)

        assertTrue(ok)
        assertEquals(listOf(CommandBuilder.disableUser(regservice)), backend.commands)
        assertTrue(store.lastResult!!.startsWith("成功"))
    }

    @Test
    fun apply_refuses_disable_user_outside_the_carrier_trio() = runBlocking {
        val backend = FakeBackend()
        val target = CloudTarget(
            packageName = "com.example.dm",
            label = "Outside",
            action = CloudAction.DISABLE_USER,
            description = "test"
        )
        val store = FakeStore(
            enabled = setOf(target.packageName),
            confirmed = setOf(target.packageName)
        )

        val ok = CloudController(backend, store).apply(listOf(target))

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_refuses_denylisted_target() = runBlocking {
        val backend = FakeBackend()
        val target = CloudTarget(
            packageName = "com.hihonor.android.pushagent",
            label = "Push",
            action = CloudAction.SUSPEND,
            description = "test"
        )
        val store = FakeStore(enabled = setOf(target.packageName))

        val ok = CloudController(backend, store).apply(listOf(target))

        assertFalse(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_suspend_target_runs_suspend_then_force_stop() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(backend, store).apply(CloudTargets.telemetry)

        assertTrue(ok)
        assertEquals(
            listOf(
                CommandBuilder.suspend(hiview),
                CommandBuilder.forceStop(hiview)
            ),
            backend.commands
        )
    }

    @Test
    fun apply_observe_only_target_is_a_noop() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(enabled = setOf("com.hihonor.cipservice"))

        val ok = CloudController(backend, store).apply(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun apply_dry_run_does_not_call_backend() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(
            enabled = setOf(regservice),
            confirmed = setOf(regservice),
            dryRun = true
        )

        val ok = CloudController(backend, store).apply(CloudTargets.carrierDm)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("成功"))
    }

    @Test
    fun apply_continues_after_a_command_failure() = runBlocking {
        val backend = FakeBackend(mutableListOf(ShellResult(1, "boom")))
        val store = FakeStore(enabled = setOf(hiview))

        val ok = CloudController(backend, store).apply(CloudTargets.telemetry)

        assertFalse(ok)
        assertEquals(
            listOf(
                CommandBuilder.suspend(hiview),
                CommandBuilder.forceStop(hiview)
            ),
            backend.commands
        )
    }

    @Test
    fun restore_all_enables_disable_user_targets() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).restoreAll(CloudTargets.carrierDm)

        assertTrue(ok)
        assertEquals(
            CloudTargets.carrierDm.map { CommandBuilder.enable(it.packageName) },
            backend.commands
        )
    }

    @Test
    fun restore_all_unsuspends_telemetry_targets() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).restoreAll(CloudTargets.telemetry)

        assertTrue(ok)
        assertEquals(
            CloudTargets.telemetry.map { CommandBuilder.unsuspend(it.packageName) },
            backend.commands
        )
    }

    @Test
    fun restore_all_ignores_enabled_flags_and_observe_only() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        val ok = CloudController(backend, store).restoreAll(CloudTargets.all)

        assertTrue(ok)
        assertEquals(6, backend.commands.size)
        assertFalse(backend.commands.any { it.contains("cipservice") })
    }

    @Test
    fun restore_all_respects_dry_run() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore(dryRun = true)

        val ok = CloudController(backend, store).restoreAll(CloudTargets.all)

        assertTrue(ok)
        assertTrue(backend.commands.isEmpty())
        assertTrue(store.lastResult!!.startsWith("成功"))
    }

    @Test
    fun last_result_is_recorded_for_restore() = runBlocking {
        val backend = FakeBackend()
        val store = FakeStore()

        CloudController(backend, store).restoreAll(CloudTargets.all)

        assertTrue(store.lastResult!!.startsWith("成功"))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.CloudControllerTest"
```

Expected: compilation fails because `CloudSettingsStore` and `CloudController` do not exist.

- [ ] **Step 3: Implement the settings store, settings implementation, and controller**

`app/src/main/java/com/nemu/hibernate/data/CloudSettingsStore.kt`:

```kotlin
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
}
```

`app/src/main/java/com/nemu/hibernate/data/CloudSettings.kt`:

```kotlin
package com.nemu.hibernate.data

import android.content.Context

/**
 * Cloud-only preferences. Uses the same "nemu" file as [Settings] but a
 * distinct "cloud_" key namespace:
 *   cloud_target_ + package name, cloud_confirmed_ + package name, cloud_last_result
 * The global dry_run key is intentionally shared with the core module.
 */
class CloudSettings(context: Context) : CloudSettingsStore {
    private val prefs = context.getSharedPreferences("nemu", Context.MODE_PRIVATE)
    private val base = Settings(context)

    override var dryRun: Boolean
        get() = base.dryRun
        set(value) {
            base.dryRun = value
        }

    override var lastResult: String?
        get() = prefs.getString("cloud_last_result", null)
        set(value) = prefs.edit().putString("cloud_last_result", value).apply()

    override fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean("cloud_target_$packageName", false)

    override fun setEnabled(packageName: String, enabled: Boolean) =
        prefs.edit().putBoolean("cloud_target_$packageName", enabled).apply()

    override fun isConfirmed(packageName: String): Boolean =
        prefs.getBoolean("cloud_confirmed_$packageName", false)

    override fun setConfirmed(packageName: String, confirmed: Boolean) =
        prefs.edit().putBoolean("cloud_confirmed_$packageName", confirmed).apply()
}
```

`app/src/main/java/com/nemu/hibernate/policy/CloudController.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.CommandBuilder
import com.nemu.hibernate.core.DenyList
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.CloudSettingsStore
import com.nemu.hibernate.privilege.PrivilegeBackend

/**
 * Applies cloud/telemetry policy. Two independent guards protect disable-user:
 * the static allowlist [CloudTargets.disableUserAllowed] and the persisted
 * per-target confirmation flag. The controller never calls uninstall.
 */
class CloudController(
    private val backend: PrivilegeBackend,
    private val store: CloudSettingsStore
) {
    suspend fun apply(targets: List<CloudTarget>): Boolean {
        var allOk = true
        var attempted = 0

        for (target in targets) {
            if (target.action == CloudAction.OBSERVE_ONLY) continue
            if (!store.isEnabled(target.packageName)) continue
            attempted++

            val reason = refusalReason(target)
            if (reason != null) {
                ActionLog.warn("拒绝云控操作：${target.packageName}（$reason）")
                allOk = false
                continue
            }

            if (!executeAll(commandsFor(target))) allOk = false
        }

        store.lastResult = when {
            attempted == 0 -> "没有启用的目标（${System.currentTimeMillis()}）"
            allOk -> "成功（${System.currentTimeMillis()}）"
            else -> "有失败或拒绝（${System.currentTimeMillis()}）"
        }
        return allOk
    }

    suspend fun restoreAll(targets: List<CloudTarget>): Boolean {
        var allOk = true

        for (target in targets) {
            if (target.action == CloudAction.OBSERVE_ONLY) continue

            val command = if (target.action == CloudAction.DISABLE_USER) {
                CommandBuilder.enable(target.packageName)
            } else {
                CommandBuilder.unsuspend(target.packageName)
            }
            if (!executeAll(listOf(command))) allOk = false
        }

        store.lastResult = if (allOk) {
            "成功（${System.currentTimeMillis()}）"
        } else {
            "有失败或拒绝（${System.currentTimeMillis()}）"
        }
        return allOk
    }

    private fun refusalReason(target: CloudTarget): String? = when {
        DenyList.isProtected(target.packageName) -> "在禁止清单中"
        target.action == CloudAction.DISABLE_USER &&
            target.packageName !in CloudTargets.disableUserAllowed ->
            "disable-user 只允许运营商 DM 三件套"
        target.action == CloudAction.DISABLE_USER &&
            !store.isConfirmed(target.packageName) ->
            "未确认停用风险"
        else -> null
    }

    private fun commandsFor(target: CloudTarget): List<String> = when (target.action) {
        CloudAction.DISABLE_USER -> listOf(CommandBuilder.disableUser(target.packageName))
        CloudAction.SUSPEND -> listOf(
            CommandBuilder.suspend(target.packageName),
            CommandBuilder.forceStop(target.packageName)
        )
        CloudAction.OBSERVE_ONLY -> emptyList()
    }

    private suspend fun executeAll(commands: List<String>): Boolean {
        var allOk = true
        for (command in commands) {
            if (store.dryRun) {
                ActionLog.info("DRY-RUN: $command")
                continue
            }
            val result = backend.exec(command)
            if (result.ok) {
                ActionLog.info("OK(${backend.name}): $command")
            } else {
                ActionLog.error(
                    "FAIL(${backend.name}, ${result.exitCode}): $command\n" +
                        result.output.take(500)
                )
                allOk = false
            }
        }
        return allOk
    }
}
```

- [ ] **Step 4: Run the controller tests to verify they pass**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.CloudControllerTest"
```

Expected: 14 tests pass.

- [ ] **Step 5: Run the whole unit-test suite**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest
```

Expected: the existing 20 tests plus 27 new tests pass (47 total at this point; Task 3 adds four more).

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/data/CloudSettingsStore.kt app/src/main/java/com/nemu/hibernate/data/CloudSettings.kt app/src/main/java/com/nemu/hibernate/policy/CloudController.kt app/src/test/java/com/nemu/hibernate/policy/CloudControllerTest.kt
git commit -m "feat: add cloud settings and guarded controller"
```

---

### Task 3: Include cloud suspend targets in the safety unfreeze path

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/policy/SafetyTargets.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/policy/BootWorker.kt`
- Test: `app/src/test/java/com/nemu/hibernate/policy/SafetyTargetsTest.kt`
- Test: `app/src/test/java/com/nemu/hibernate/policy/SafetyUnfreezerTest.kt` (add one test)

**Interfaces:**
- Consumes: `Targets`, `CloudTargets`, `CommandBuilder.unsuspendAll`, `PrivilegeBackend`.
- Produces: `SafetyTargets.suspendPackageNames`, `SafetyUnfreezer.unsuspendPackages(backend, packages)`. The existing `unsuspendAll(backend, targets)` remains and delegates, so no other caller breaks.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/nemu/hibernate/policy/SafetyTargetsTest.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.Targets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTargetsTest {
    @Test
    fun includes_all_core_targets() {
        Targets.all.forEach {
            assertTrue(it.packageName, it.packageName in SafetyTargets.suspendPackageNames)
        }
    }

    @Test
    fun includes_cloud_suspend_targets_only() {
        assertTrue("com.hihonor.hiview" in SafetyTargets.suspendPackageNames)
        assertTrue("com.hihonor.hiviewpush" in SafetyTargets.suspendPackageNames)
        assertTrue("com.hihonor.hiviewtunnel" in SafetyTargets.suspendPackageNames)
        assertFalse("com.hihonor.regservice" in SafetyTargets.suspendPackageNames)
        assertFalse("com.hihonor.android.UEInfoCheck" in SafetyTargets.suspendPackageNames)
        assertFalse("com.hihonor.android.AutoRegSms" in SafetyTargets.suspendPackageNames)
    }

    @Test
    fun has_no_duplicates() {
        assertEquals(
            SafetyTargets.suspendPackageNames.size,
            SafetyTargets.suspendPackageNames.distinct().size
        )
    }
}
```

Append to `app/src/test/java/com/nemu/hibernate/policy/SafetyUnfreezerTest.kt`:

```kotlin
    @Test
    fun unsuspendPackages_uses_the_given_package_list() = runBlocking {
        val backend = FakeBackend()

        val ok = SafetyUnfreezer.unsuspendPackages(
            backend,
            listOf("com.hihonor.hiview", "com.hihonor.hiviewpush")
        )

        assertTrue(ok)
        assertEquals(
            "pm unsuspend --user 0 'com.hihonor.hiview' 'com.hihonor.hiviewpush'",
            backend.lastCommand
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.SafetyTargetsTest" --tests "com.nemu.hibernate.policy.SafetyUnfreezerTest"
```

Expected: compilation fails because `SafetyTargets` and `SafetyUnfreezer.unsuspendPackages` do not exist.

- [ ] **Step 3: Implement `SafetyTargets` and the package-list overload**

`app/src/main/java/com/nemu/hibernate/policy/SafetyTargets.kt`:

```kotlin
package com.nemu.hibernate.policy

import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.Targets

/**
 * Every package Nemu may leave suspended across a process lifetime.
 * Carrier DM targets are disabled-user, not suspended, so they are not here;
 * their rollback is the explicit "恢复全部云控" action.
 */
object SafetyTargets {
    val suspendPackageNames: List<String> =
        (Targets.all.map { it.packageName } + CloudTargets.suspendPackageNames).distinct()
}
```

Replace `app/src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt` with:

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
    suspend fun unsuspendAll(backend: PrivilegeBackend, targets: List<Target>): Boolean =
        unsuspendPackages(backend, targets.map { it.packageName })

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

Replace `app/src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt` with:

```kotlin
package com.nemu.hibernate.policy

import android.content.Context
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
                val backend = BackendManager(settings).getForShutdown()
                if (backend == null) {
                    "无可用权限"
                } else if (
                    SafetyUnfreezer.unsuspendPackages(
                        backend,
                        SafetyTargets.suspendPackageNames
                    )
                ) {
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

Replace the `Targets` import and the `SafetyUnfreezer.unsuspendAll` call in `app/src/main/java/com/nemu/hibernate/policy/BootWorker.kt`. The full file becomes:

```kotlin
package com.nemu.hibernate.policy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.privilege.BackendManager
import com.nemu.hibernate.privilege.PrivilegeBackend
import kotlinx.coroutines.delay

/**
 * Boot safety pass: unsuspend every package Nemu may have suspended and then
 * stop. The old "re-apply the freeze policy after boot" behaviour was removed
 * deliberately — targets stay unfrozen until the user taps Apply.
 */
class BootWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = Settings(applicationContext)
        val manager = BackendManager(settings)

        var backend: PrivilegeBackend? = null
        var attempts = 0
        while (backend == null && attempts < 6) {
            backend = manager.get()
            if (backend == null) {
                delay(15_000)
                attempts++
            }
        }
        if (backend == null) {
            ActionLog.warn("开机安全解冻失败：无可用权限")
            settings.lastBootResult =
                "安全解冻失败：无可用权限（${System.currentTimeMillis()}）"
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }

        val ok = SafetyUnfreezer.unsuspendPackages(
            backend,
            SafetyTargets.suspendPackageNames
        )
        val resultText = if (ok) "安全解冻成功" else "安全解冻失败"
        ActionLog.info("开机$resultText")
        settings.lastBootResult = "$resultText（${System.currentTimeMillis()}）"
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
```

- [ ] **Step 4: Run the policy tests to verify they pass**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.*"
```

Expected: `CloudControllerTest` (14), `SafetyTargetsTest` (3), and `SafetyUnfreezerTest` (4) pass.

- [ ] **Step 5: Run the whole unit-test suite**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all tests pass; expected total 51.

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/policy/SafetyTargets.kt app/src/main/java/com/nemu/hibernate/policy/SafetyUnfreezer.kt app/src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt app/src/main/java/com/nemu/hibernate/policy/BootWorker.kt app/src/test/java/com/nemu/hibernate/policy/SafetyTargetsTest.kt app/src/test/java/com/nemu/hibernate/policy/SafetyUnfreezerTest.kt
git commit -m "feat: include cloud suspend targets in safety unfreeze"
```

---

### Task 4: Package status reader, cloud UI section, and app wiring

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/privilege/PackageStatusReader.kt`
- Create: `app/src/main/java/com/nemu/hibernate/ui/CloudSection.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: everything from Tasks 1-3, plus `PackageManager` APIs and `CloudSettings`.
- Produces: `PackageStatusReader`, `AndroidPackageStatusReader`, `CloudUiState`, `CloudSection`; the main screen now shows the cloud section and the activity wires it.

- [ ] **Step 1: Implement the platform status reader**

The pure status text is already tested in Task 1. The reader is a thin adapter over verified platform APIs (`getApplicationEnabledSetting` and `isPackageSuspended` exist in `android-37.2/android.jar`).

`app/src/main/java/com/nemu/hibernate/privilege/PackageStatusReader.kt`:

```kotlin
package com.nemu.hibernate.privilege

import android.content.Context
import android.content.pm.PackageManager
import com.nemu.hibernate.core.CloudStatus

interface PackageStatusReader {
    fun read(packageName: String): CloudStatus?
}

/**
 * Read-only package state. `getApplicationEnabledSetting` reports the
 * per-user enabled state (disabled-user is COMPONENT_ENABLED_STATE_DISABLED_USER),
 * and `isPackageSuspended` reports the suspend flag. Both are public APIs.
 * Package visibility is covered by QUERY_ALL_PACKAGES and the manifest queries.
 */
class AndroidPackageStatusReader(private val context: Context) : PackageStatusReader {
    override fun read(packageName: String): CloudStatus? = runCatching {
        val pm = context.packageManager
        val setting = pm.getApplicationEnabledSetting(packageName)
        val enabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val suspended = pm.isPackageSuspended(packageName)
        CloudStatus(enabled = enabled, suspended = suspended)
    }.getOrNull()
}
```

- [ ] **Step 2: Implement the cloud UI section**

`app/src/main/java/com/nemu/hibernate/ui/CloudSection.kt`:

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
import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudStatus
import com.nemu.hibernate.core.CloudTarget

data class CloudUiState(
    val targets: List<CloudTarget>,
    val enabled: Map<String, Boolean>,
    val statuses: Map<String, CloudStatus>,
    val lastResult: String,
    val busy: Boolean,
    val dryRun: Boolean
)

@Composable
fun CloudSection(
    state: CloudUiState,
    onToggle: (String, Boolean) -> Unit,
    onApply: () -> Unit,
    onRestoreAll: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    var pendingConfirm by remember { mutableStateOf<CloudTarget?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "云控 / 遥测（高级，默认关闭）",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "运营商 DM 三件套使用“停用（pm disable-user）”而不是 suspend；" +
                        "这是本模块的停用例外，不会卸载包。" +
                        "停用可能影响运营商服务（VoLTE 配置、FOTA、自注册短信）。" +
                        "遥测三项使用 suspend + force-stop，可能影响诊断日志。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "“恢复全部云控”会对三件套执行 pm enable、对遥测三项执行 pm unsuspend；不会取消勾选。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (state.dryRun) {
                        "Dry run：只记录命令，不执行。"
                    } else {
                        "执行模式：会实际写入。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "上次云控操作：${state.lastResult}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApply, enabled = !state.busy) {
                        Text("立即应用云控")
                    }
                    OutlinedButton(onClick = onRestoreAll, enabled = !state.busy) {
                        Text("恢复全部云控")
                    }
                }
                TextButton(onClick = onRefreshStatus, enabled = !state.busy) {
                    Text("刷新状态")
                }
            }
        }

        state.targets.forEach { target ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(target.label, style = MaterialTheme.typography.titleSmall)
                        Text(target.packageName, style = MaterialTheme.typography.bodySmall)
                        Text(target.description, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "操作：${actionLabel(target.action)}；" +
                                "策略：${
                                    when {
                                        target.action == CloudAction.OBSERVE_ONLY -> "仅观察"
                                        state.enabled[target.packageName] == true -> "已纳入"
                                        else -> "未纳入"
                                    }
                                }；" +
                                "状态：${statusText(target, state.statuses[target.packageName])}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (target.action != CloudAction.OBSERVE_ONLY) {
                        Switch(
                            checked = state.enabled[target.packageName] == true,
                            onCheckedChange = { checked ->
                                if (checked && target.action == CloudAction.DISABLE_USER) {
                                    pendingConfirm = target
                                } else {
                                    onToggle(target.packageName, checked)
                                }
                            },
                            enabled = !state.busy
                        )
                    }
                }
            }
        }
    }

    val confirmTarget = pendingConfirm
    if (confirmTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text("确认停用 ${confirmTarget.label}") },
            text = {
                Text(
                    "${confirmTarget.packageName}\n\n" +
                        "将执行 pm disable-user --user 0（不卸载）。" +
                        "停用后该组件的云端管理/上报停止，" +
                        "但可能影响运营商服务（VoLTE 配置、FOTA、自注册短信）。" +
                        "可用“恢复全部云控”执行 pm enable 回滚。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onToggle(confirmTarget.packageName, true)
                    pendingConfirm = null
                }) {
                    Text("确认停用")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun statusText(target: CloudTarget, status: CloudStatus?): String =
    status?.describe(target.action) ?: "未知（读取失败或未安装）"

private fun actionLabel(action: CloudAction): String = when (action) {
    CloudAction.DISABLE_USER -> "disable-user（可逆）"
    CloudAction.SUSPEND -> "suspend + force-stop"
    CloudAction.OBSERVE_ONLY -> "仅观察"
}
```

- [ ] **Step 3: Modify `MainScreen.kt`**

Change the function signature to:

```kotlin
fun MainScreen(
    backendStatus: String,
    lastShutdownResult: String,
    lastBootResult: String,
    targets: List<Target>,
    enabled: Map<String, Boolean>,
    dryRun: Boolean,
    logs: List<LogEntry>,
    busy: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDryRunChange: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
    onApply: () -> Unit,
    onRestore: () -> Unit,
    cloud: CloudUiState,
    onCloudToggle: (String, Boolean) -> Unit,
    onCloudApply: () -> Unit,
    onCloudRestoreAll: () -> Unit,
    onCloudRefreshStatus: () -> Unit
)
```

Replace the top-card text:

```kotlin
                        Text(
                            "只 suspend，不卸载/停用系统应用。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

with:

```kotlin
                        Text(
                            "核心冻结只 suspend，不卸载系统应用；" +
                                "云控区的运营商 DM 三件套是可逆 disable-user 例外。",
                            style = MaterialTheme.typography.bodySmall
                        )
```

Insert this item after the core `items(targets) { ... }` block and before `item { Text("日志", ...) }`:

```kotlin
            item {
                CloudSection(
                    state = cloud,
                    onToggle = onCloudToggle,
                    onApply = onCloudApply,
                    onRestoreAll = onCloudRestoreAll,
                    onRefreshStatus = onCloudRefreshStatus
                )
            }
```

No new imports are needed in `MainScreen.kt`: `CloudSection` and `CloudUiState` are in the same `ui` package.

- [ ] **Step 4: Wire the module in `MainActivity.kt`**

Add imports:

```kotlin
import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudStatus
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.data.CloudSettings
import com.nemu.hibernate.policy.CloudController
import com.nemu.hibernate.privilege.AndroidPackageStatusReader
import com.nemu.hibernate.privilege.PackageStatusReader
import com.nemu.hibernate.ui.CloudUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Add fields next to the existing `settings`/`manager` fields:

```kotlin
    private lateinit var settings: Settings
    private lateinit var cloudSettings: CloudSettings
    private lateinit var manager: BackendManager
    private lateinit var statusReader: PackageStatusReader
```

In `onCreate`, replace the existing initialization block:

```kotlin
        settings = Settings(this)
        manager = BackendManager(settings)
```

with:

```kotlin
        settings = Settings(this)
        cloudSettings = CloudSettings(this)
        manager = BackendManager(settings)
        statusReader = AndroidPackageStatusReader(this)
```

Add the status helper to `MainActivity` (outside `onCreate`):

```kotlin
    private suspend fun readCloudStatuses(): Map<String, CloudStatus> =
        withContext(Dispatchers.IO) {
            CloudTargets.all.mapNotNull { target ->
                statusReader.read(target.packageName)?.let { target.packageName to it }
            }.toMap()
        }
```

Inside `setContent`, after the existing `val enabled = remember { mutableStateMapOf<String, Boolean>() }`, add:

```kotlin
                val cloudEnabled = remember { mutableStateMapOf<String, Boolean>() }
                var cloudBusy by remember { mutableStateOf(false) }
                var cloudLastResult by remember {
                    mutableStateOf(cloudSettings.lastResult ?: "尚未执行")
                }
                var cloudStatuses by remember {
                    mutableStateOf<Map<String, CloudStatus>>(emptyMap())
                }
```

Extend the existing `LaunchedEffect(Unit)` block to seed cloud state:

```kotlin
                LaunchedEffect(Unit) {
                    Targets.all.forEach {
                        enabled[it.packageName] = settings.isEnabled(it.packageName)
                    }
                    CloudTargets.all.forEach {
                        cloudEnabled[it.packageName] = cloudSettings.isEnabled(it.packageName)
                    }
                    cloudStatuses = readCloudStatuses()
                    backendStatus = manager.status()
                }
```

Add these arguments to the existing `MainScreen(...)` call, after `onRestore = { ... }`:

```kotlin
                    cloud = CloudUiState(
                        targets = CloudTargets.all,
                        enabled = cloudEnabled,
                        statuses = cloudStatuses,
                        lastResult = cloudLastResult,
                        busy = cloudBusy,
                        dryRun = dryRun
                    ),
                    onCloudToggle = { packageName, value ->
                        cloudSettings.setEnabled(packageName, value)
                        val target = CloudTargets.byPackage(packageName)
                        cloudSettings.setConfirmed(
                            packageName,
                            value && target?.action == CloudAction.DISABLE_USER
                        )
                        cloudEnabled[packageName] = value
                    },
                    onCloudApply = {
                        scope.launch {
                            cloudBusy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；无法执行云控操作")
                                cloudSettings.lastResult = "失败：无可用权限"
                                cloudLastResult = cloudSettings.lastResult ?: "尚未执行"
                            } else {
                                CloudController(backend, cloudSettings)
                                    .apply(CloudTargets.all)
                                cloudLastResult =
                                    cloudSettings.lastResult ?: "尚未执行"
                                cloudStatuses = readCloudStatuses()
                                backendStatus = manager.status()
                            }
                            cloudBusy = false
                        }
                    },
                    onCloudRestoreAll = {
                        scope.launch {
                            cloudBusy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；无法恢复云控")
                                cloudSettings.lastResult = "失败：无可用权限"
                                cloudLastResult = cloudSettings.lastResult ?: "尚未执行"
                            } else {
                                CloudController(backend, cloudSettings)
                                    .restoreAll(CloudTargets.all)
                                cloudLastResult =
                                    cloudSettings.lastResult ?: "尚未执行"
                                cloudStatuses = readCloudStatuses()
                                backendStatus = manager.status()
                            }
                            cloudBusy = false
                        }
                    },
                    onCloudRefreshStatus = {
                        scope.launch {
                            cloudStatuses = readCloudStatuses()
                        }
                    }
```

- [ ] **Step 5: Add the cloud packages to manifest queries**

In `app/src/main/AndroidManifest.xml`, inside `<queries>`, after the existing `com.hihonor.android.AutoRegSms` entry, add:

```xml
        <package android:name="com.hihonor.hiview" />
        <package android:name="com.hihonor.hiviewpush" />
        <package android:name="com.hihonor.hiviewtunnel" />
        <package android:name="com.hihonor.cipservice" />
```

Do not add `com.hihonor.android.pushagent`: it is excluded from the module and remains in `DenyList`.

- [ ] **Step 6: Build and run all tests**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: all 51 unit tests pass and `BUILD SUCCESSFUL`. APK: `app\build\outputs\apk\debug\app-debug.apk`.

- [ ] **Step 7: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/privilege/PackageStatusReader.kt app/src/main/java/com/nemu/hibernate/ui/CloudSection.kt app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt app/src/main/java/com/nemu/hibernate/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add cloud/telemetry UI section and status reader"
```

---

### Task 5: Documentation updates

**Files:**
- Modify: `README.md`
- Modify: `docs/03-design.md`
- Modify: `docs/02-package-inventory.md`
- Modify: `docs/04-test-plan.md`

**Interfaces:**
- Consumes: the implemented module from Tasks 1-4.
- Produces: user-facing and test-facing documentation that matches the implementation.

- [ ] **Step 1: Update `README.md`**

Replace the status bullet:

```markdown
- 后续可选：运营商云控、遥测、反诈/温控相关组件（默认关闭，需单独确认）。
```

with:

```markdown
- 2026-11-21：云控/遥测模块代码完成，待实机验收。运营商 DM 三件套为可逆 `disable-user`，遥测三项为 `suspend`，默认关闭、逐项确认。
- 反诈/温控相关组件仍为后续可选模块（默认关闭，需单独确认）。
```

Add this section after `## 安全解冻（始终开启）` and before `## 文档`:

```markdown
## 云控 / 遥测（高级，默认关闭）

- 运营商 DM 三件套（`com.hihonor.regservice`、`com.hihonor.android.UEInfoCheck`、`com.hihonor.android.AutoRegSms`）使用 `pm disable-user --user 0`，是本模块的“停用”例外；逐项确认，不卸载。
- 遥测三项（`com.hihonor.hiview`、`com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel`）使用 `pm suspend --user 0` + `am force-stop`。
- `com.hihonor.cipservice` 仅展示状态，不提供操作；`com.hihonor.android.pushagent` 在禁止清单中，不提供操作。
- “恢复全部云控”：三件套 `pm enable --user 0`，遥测三项 `pm unsuspend --user 0`；不会取消勾选。
- 风险：停用运营商 DM 可能影响 VoLTE 配置、运营商 FOTA、自注册短信；遥测 suspend 可能影响诊断日志。验证步骤见 `docs/04-test-plan.md` CT1–CT5。
```

Replace the non-goal bullet:

```markdown
- 不做卸载/停用系统应用（只 suspend）。
```

with:

```markdown
- 不做卸载；系统应用默认不做停用。云控模块的例外是运营商 DM 三件套的 `disable-user`（可逆、逐项确认，见 `docs/03-design.md` §7.1）。
```

Replace the non-goal bullet:

```markdown
- 不默认处理反诈/温控/云控；这些作为单独的高级模块，需明确开关和风险确认。
```

with:

```markdown
- 云控/遥测为默认关闭的高级模块；反诈/温控作为单独的高级模块，需明确开关和风险确认。
```

- [ ] **Step 2: Update `docs/03-design.md`**

Replace the top status line:

```markdown
> 状态：P1/P2 代码完成，待实机验收；P3/P4 未开始。
```

with:

```markdown
> 状态：P1/P2 代码完成；P3 云控/遥测代码完成，待实机验收；P3 反诈与 P4 未开始。
```

Replace §4.5 item 1:

```markdown
1. **Never uninstall / never disable**：代码中不存在 `pm uninstall` / `pm disable-user` 调用路径。
```

with:

```markdown
1. **Never uninstall / never disable（运营商 DM 例外）**：代码中不存在 `pm uninstall`；在云控模块中，`pm disable-user` 仅存在于运营商 DM 三件套路径（`CloudController`，静态白名单 + 逐项确认 + `pm enable` 回滚）。
```

Replace the §7.1 table and the existing “例外说明” paragraph with the table plus implementation notes below (the notes absorb the exception text):

````markdown
| 目标 | 默认操作 | 实现（2026-11-21） |
|---|---|---|
| `com.hihonor.regservice`（伪装名“系统消息”，实际 CMCC DM） | `disable-user`（可逆） | `CloudController` 逐项确认后执行 `pm disable-user --user 0`；不卸载 |
| `com.hihonor.android.UEInfoCheck` | `disable-user`（可逆） | 同上 |
| `com.hihonor.android.AutoRegSms` | `disable-user`（可逆） | 同上 |
| `com.hihonor.hiview` / `hiviewpush` / `hiviewtunnel` | `suspend` + `force-stop`，默认关闭 | `CloudController` 执行 `pm suspend` + `am force-stop`；纳入 `SafetyTargets` 关机/开机安全解冻 |
| `com.hihonor.cipservice` | 仅展示，默认不动 | `CloudAction.OBSERVE_ONLY`，无执行路径 |
| `com.hihonor.android.pushagent` | 不提供一键操作 | 在 `DenyList` 中，`CloudTargets` 不包含 |

**实现约束（2026-11-21）**：

- 运营商 DM 三件套是云控核心，`suspend` 不能阻止其后台/开机接收器，因此该模块允许 `disable-user`（不是 uninstall），并在 UI 中逐项确认。
- `CloudTargets.disableUserAllowed` 精确等于三件套；控制器对任何其他 `DISABLE_USER` 目标 fail-closed。
- `CloudSettings.isConfirmed` 必须为 true；UI 在打开三件套开关时弹出确认框，取消则不写入。
- 回滚 `CloudController.restoreAll` 对三件套执行 `pm enable --user 0`、对遥测三项执行 `pm unsuspend --user 0`；忽略勾选状态，因此清空 App 数据后仍可回滚；不取消勾选。`pm enable` 将 enabled 状态置为 DEFAULT（通常等同启用）。
- 状态回读只使用 `PackageManager.getApplicationEnabledSetting` 与 `isPackageSuspended`（只读）；核心目标的状态回读仍推迟到 P4。
- 设置键：`cloud_target_` + 包名、`cloud_confirmed_` + 包名、`cloud_last_result`；`dry_run` 与核心共享。
- 遥测三项的 suspend 纳入 `SafetyTargets.suspendPackageNames`，正常关机/重启前会 `unsuspend`；三件套的 disable-user 不参与安全解冻，保持禁用直到显式回滚。
- 本模块不执行 `pm uninstall`、不清缓存、不做组件级禁用。
````

Replace the §7.4 P3 row:

```markdown
| P3 | 运营商 DM/遥测模块（disable-user + 回滚）、反诈 `numberidentity` + AppOps 限制 | 未开始 |
```

with:

```markdown
| P3 | 运营商 DM/遥测模块（disable-user + 回滚）、反诈 `numberidentity` + AppOps 限制 | 云控/遥测代码完成，待实机验收；反诈未开始 |
```

Also replace the final `> 状态说明` paragraph's last line if needed:

```markdown
> 2026-11-21 已实测 Shizuku 后端、绿色包/OUC 冻结与正常重启安全解冻；Root 后端未测。
```

with:

```markdown
> 2026-11-21 已实测 Shizuku 后端、绿色包/OUC 冻结与正常重启安全解冻；Root 后端未测。
> 云控/遥测模块同日完成代码与单元测试，实机结果见 `docs/04-test-plan.md` 的 CT1–CT5。
```

- [ ] **Step 3: Update `docs/02-package-inventory.md` §4**

In the §4 table, replace the recommendation cells:

```markdown
| `com.hihonor.regservice` | **“系统消息”**（伪装名） | 15.10.0.104 | 实际是 `HnCmccDm` 中国移动终端管理 DM：`cn.richinfo.dm.*`，自注册、心跳、FOTA、应用清单上报 | **绿/黄** | **建议停用**；TelemetryCleaner 已实测；可逆 |
| `com.hihonor.android.UEInfoCheck` | （UEInfoCheck） | - | 电信数据上报组件 | 绿/黄 | 建议停用；TelemetryCleaner 已实测 |
| `com.hihonor.android.AutoRegSms` | （ChnTelecomAutoReg） | - | 电信自注册短信 | 绿/黄 | 建议停用；TelemetryCleaner 已实测 |
| `com.hihonor.hiview` | hiview | 17.0.0.001 | 遥测采集 | 黄 | 可选停用；可能影响诊断/用户体验计划 |
| `com.hihonor.hiviewpush` | （无 label） | 17.0.0.001 | 遥测推送 | 黄 | 可选 |
| `com.hihonor.hiviewtunnel` | HiViewTunnel | 17.0.0.000 | 遥测上传通道 | 黄 | 可选 |
| `com.hihonor.cipservice` | HnCIPService | 10.0.0.1 | 含 `SET_CHR_DATA`、定位/Wi-Fi 权限，疑似 CHR 上报 | 黄 | 可选；先观察 |
| `com.hihonor.android.pushagent` | 荣耀推送服务 | 11.0.11.313 | Honor Push 通道 | 黄 | 停用会失去荣耀推送；不建议默认停 |
```

with:

```markdown
| `com.hihonor.regservice` | **“系统消息”**（伪装名） | 15.10.0.104 | 实际是 `HnCmccDm` 中国移动终端管理 DM：`cn.richinfo.dm.*`，自注册、心跳、FOTA、应用清单上报 | **绿/黄** | **Nemu 提供 `disable-user`（可逆、逐项确认）**；不卸载 |
| `com.hihonor.android.UEInfoCheck` | （UEInfoCheck） | - | 电信数据上报组件 | 绿/黄 | Nemu 提供 `disable-user`（可逆、逐项确认） |
| `com.hihonor.android.AutoRegSms` | （ChnTelecomAutoReg） | - | 电信自注册短信 | 绿/黄 | Nemu 提供 `disable-user`（可逆、逐项确认） |
| `com.hihonor.hiview` | hiview | 17.0.0.001 | 遥测采集 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.hiviewpush` | （无 label） | 17.0.0.001 | 遥测推送 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.hiviewtunnel` | HiViewTunnel | 17.0.0.000 | 遥测上传通道 | 黄 | Nemu 提供 `suspend` + `force-stop`（可逆；默认关闭） |
| `com.hihonor.cipservice` | HnCIPService | 10.0.0.1 | 含 `SET_CHR_DATA`、定位/Wi-Fi 权限，疑似 CHR 上报 | 黄 | Nemu 仅展示状态，不提供操作（先观察） |
| `com.hihonor.android.pushagent` | 荣耀推送服务 | 11.0.11.313 | Honor Push 通道 | 黄 | Nemu 不提供操作；在 `DenyList` 中 |
```

Add a note after the §4 table:

```markdown
> 2026-11-21：Nemu 云控模块实现 `CloudTargets` / `CloudController`。运营商 DM 三件套是云控模块中唯一允许 `pm disable-user` 的目标；遥测三项固定用 `suspend`，不使用 `disable-user`。
```

- [ ] **Step 4: Update `docs/04-test-plan.md`**

Insert after T10 (before `## 3. 验收标准`). The cloud tests use the `CT` prefix so they do not collide with the T11+ IDs used by the anti-fraud and COTA plans:

````markdown
### CT1：运营商 DM 三件套 disable-user + 回滚（逐包，用户执行）

**目的**：验证 `pm disable-user --user 0` 的可逆停用、重启持久性与 `pm enable` 回滚；一次只测一个包。

**前置（用户执行，只读）**：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
```

记录基线：`pm list packages -d` 不应包含目标；`User 0:` 行记录 `enabled=` 的当前值。

**步骤（以 `com.hihonor.regservice` 为例；另外两个包重复同样步骤）**：

1. 打开 Nemu，保持 Dry run ON。在“云控 / 遥测”区打开 `系统消息（实际：中国移动终端管理 DM）` 开关，确认弹出停用警告。
2. 点“立即应用云控”。期望日志只出现：
   `DRY-RUN: pm disable-user --user 0 'com.hihonor.regservice'`
   且 `pm list packages -d` 无变化。
3. 关闭 Dry run，再点“立即应用云控”。期望日志出现：
   `OK(Root) 或 OK(Shizuku): pm disable-user --user 0 'com.hihonor.regservice'`
4. 只读验证：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
adb shell "ps -A | grep -i regservice"
```

期望：`pm list packages -d` 包含 `package:com.hihonor.regservice`；`User 0:` 行显示 `enabled=3`（`COMPONENT_ENABLED_STATE_DISABLED_USER`）；进程无输出。App 内“状态”应显示“已停用”（点“刷新状态”）。
5. 正常重启（电源菜单或 `adb shell svc power reboot`，不要用 `adb reboot`）。开机后重复第 4 步：目标应仍为 `enabled=3`，`pm list packages -d` 仍包含它。
6. 回滚：关闭 Dry run，点“恢复全部云控”。期望日志出现：
   `OK(Root) 或 OK(Shizuku): pm enable --user 0 'com.hihonor.regservice'`
7. 只读验证：

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
```

期望：不再出现在 `pm list packages -d`；`User 0:` 行显示 `enabled=0`；App 状态显示“已启用”。

**通过标准**：Dry run 不写入；实际执行后 `enabled=3` 且重启保持；`pm enable` 后 `enabled=0`；无系统异常。

**失败处理**：立即执行第 5 节回滚命令；若 adb 不可用，用 Shizuku 终端或 KSU root 终端执行同样的 `pm enable`。

### CT2：遥测三项 suspend + 安全解冻（用户执行）

**目的**：验证 `pm suspend` + `am force-stop`，以及 `SafetyTargets` 在正常重启前对遥测 suspend 的安全解冻。

**步骤（以 `com.hihonor.hiview` 为例）**：

1. 打开 Nemu（启动 `SafetyService`），保持 Dry run ON，打开 `hiview（遥测采集）` 开关（不应弹停用警告），点“立即应用云控”。期望日志：
   `DRY-RUN: pm suspend --user 0 'com.hihonor.hiview'`
   `DRY-RUN: am force-stop 'com.hihonor.hiview'`
2. 关闭 Dry run，点“立即应用云控”。期望两条 `OK(Root)` 或 `OK(Shizuku)` 日志。
3. 只读验证：

```powershell
adb shell dumpsys package com.hihonor.hiview | Select-String 'User 0:'
adb shell "ps -A | grep -i hiview"
```

期望：`suspended=true`；进程通常无输出（suspend 不阻止后台，进程可能被系统重新拉起，进程存在不作为失败）。
4. 正常重启（`adb shell svc power reboot` 或电源菜单）。开机后验证：

```powershell
adb shell dumpsys package com.hihonor.hiview | Select-String 'User 0:'
```

期望：`suspended=false`（`SafetyTargets` 已将其纳入关机/开机安全解冻）。若使用 `adb reboot`，本项不成立；不要用 `adb reboot` 验收。
5. 重新应用 suspend，然后点“恢复全部云控”，验证 `suspended=false`。
6. 对 `com.hihonor.hiviewpush`、`com.hihonor.hiviewtunnel` 各重复一次（可合并执行一次“立即应用云控”，但验证时逐包检查）。

**通过标准**：apply 后 `suspended=true`；正常重启后 `suspended=false`；restore 后 `suspended=false`；手机正常启动。

### CT3：仅观察与状态回读（用户执行）

1. `com.hihonor.cipservice` 行显示“操作：仅观察”，没有开关；状态由 `PackageManager` 回读显示。
2. Dry run OFF，点“立即应用云控”和“恢复全部云控”，日志中不应出现任何 `cipservice` 命令。
3. 对三件套执行 CT1 的 disable 后，点“刷新状态”，三件套行应显示“已停用”；回滚后显示“已启用”。

**通过标准**：`cipservice` 无操作路径；状态与实际 `dumpsys`/`pm list packages -d` 一致。

### CT4：逐项确认与守卫（用户执行 + 代码级）

1. 在云控区打开 `系统消息（实际：中国移动终端管理 DM）` 开关：必须弹出停用确认框；点“取消”后开关保持关闭。
2. 再次打开并点“确认停用”：开关变为开启。
3. 打开 `hiview（遥测采集）`：不应弹确认框。
4. 代码级：

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.policy.CloudControllerTest"
```

期望：14 个测试通过，其中覆盖未确认拒绝、非白名单 disable-user 拒绝、DenyList 拒绝。

### CT5：无卸载与调用面检查（代码级）

```powershell
cd D:\win_root\nemu
grep -rni "pm uninstall" app/src
grep -rn "CommandBuilder.disableUser" app/src/main/java/com/nemu/hibernate/policy/CloudController.kt
```

期望：第一条无匹配；第二条在 `CloudController.kt` 出现一次（受守卫调用）。`core/CommandBuilder.kt` 中是 `fun disableUser` 定义，不含 `CommandBuilder.disableUser` 字样；其他模块的 disable-user 调用点不属于本次检查。

**通过标准**：不存在 `pm uninstall`；云控模块中 `pm disable-user` 只有一个生产调用点且受 `CloudTargets.disableUserAllowed` 与 `isConfirmed` 保护。
````

Update the acceptance section line:

```markdown
- [x] 代码中不存在 `pm uninstall` / `pm disable-user` 调用路径。
```

to:

```markdown
- [x] 代码中不存在 `pm uninstall`。核心版本无 `pm disable-user` 调用路径；云控模块中，`pm disable-user` 仅存在于 `CloudController`，受三件套白名单 + 逐项确认保护（CT1/CT4/CT5）。
```

Add to §4 risk table:

```markdown
| 停用运营商 DM 影响 VoLTE/FOTA/自注册短信 | 中 | 中 | 逐项确认；一次只停一个；随时 `pm enable` 回滚并重启 |
| 系统更新/运营商配置重新启用云控组件 | 中 | 中 | App 状态回读 + 定期检查；重新应用 |
| hiview suspend 被系统重新拉起 | 低 | 中 | suspend + force-stop；接受可能被绑定拉起，必要时重新应用 |
| Nemu 被卸载/清数据后 disable 状态残留 | 中 | 低 | “恢复全部云控”不依赖勾选；或用第 5 节 `pm enable` |
```

Add a note under §7.1 C5:

```markdown
> 云控模块（2026-11-21）之后，`CommandBuilder.disableUser` 有一个生产调用点 `CloudController.commandsFor`，受 `CloudTargets.disableUserAllowed` 和 `CloudSettings.isConfirmed` 双重保护；C5 记录的是当时核心版本的状态。
```

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add README.md docs/03-design.md docs/02-package-inventory.md docs/04-test-plan.md
git commit -m "docs: document cloud/telemetry module and tests"
```

---

### Task 6: Final build and user-executed device acceptance

**Files:**
- Modify: `docs/04-test-plan.md` (record actual device results only after the user runs them)
- No source changes expected unless the unit tests or the build expose a defect; if that happens, return to the owning task and rerun all of its steps.

**Interfaces:**
- Consumes: the APK from Task 4/5 and the user's device results.
- Produces: a verified build, recorded test status, and an acceptance decision.

- [ ] **Step 1: Clean build and full unit-test run**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; all unit tests pass (expected 51). If a test fails, fix the corresponding task and rerun this step; do not proceed.

- [ ] **Step 2: Record APK identity**

Run:

```powershell
Get-Item D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk | Select-Object FullName, Length
Get-FileHash D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Record path, byte size, and SHA-256 in `docs/04-test-plan.md` §7.1 as a new C-row. Do not install the APK yourself.

- [ ] **Step 3: Hand the device checklist to the user**

The user executes, in order:

1. `adb install -r D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`
2. Open Nemu and confirm the cloud section appears below the update/AI list.
3. Run CT1 for `com.hihonor.regservice`, then `com.hihonor.android.UEInfoCheck`, then `com.hihonor.android.AutoRegSms` (one at a time; reboot after each disable; rollback after each).
4. Run CT2 for `com.hihonor.hiview`, `com.hihonor.hiviewpush`, `com.hihonor.hiviewtunnel`.
5. Run CT3, CT4, and CT5.

The implementer must not run any `adb install`, `adb shell pm ...`, reboot, or write command. Read-only `grep`/Gradle commands on the PC are allowed.

- [ ] **Step 4: Record results honestly**

For every CT1-CT4 device row and the CT5 code-level row, record date, test ID, `通过` / `失败` / `未测`, backend (`Root`/`Shizuku`), exact observed `dumpsys`/`pm` output, and rollback result. Any test not executed must be recorded as `未测`, never `通过`.

If a test fails:
- Stop further cloud tests.
- Execute the rollback section below.
- Record the failure and observed output.
- Fix the code, rerun Task 6 Step 1, and have the user retest.

- [ ] **Step 5: Update acceptance status and commit**

Mark the cloud acceptance checkboxes in `docs/04-test-plan.md` based only on actual results. Then:

```powershell
cd D:\win_root\nemu
git add docs/04-test-plan.md
git commit -m "docs: record cloud/telemetry acceptance results"
```

---

## Rollback

### In-app rollback

1. Open Nemu.
2. Turn Dry run OFF (otherwise rollback only logs commands).
3. Tap "恢复全部云控".
4. Expected log entries:
   - `pm enable --user 0 'com.hihonor.regservice'`
   - `pm enable --user 0 'com.hihonor.android.UEInfoCheck'`
   - `pm enable --user 0 'com.hihonor.android.AutoRegSms'`
   - `pm unsuspend --user 0 'com.hihonor.hiview'`
   - `pm unsuspend --user 0 'com.hihonor.hiviewpush'`
   - `pm unsuspend --user 0 'com.hihonor.hiviewtunnel'`
5. Verify with CT1/CT2 read-only commands.

Rollback works even if the toggles are off or app data was cleared; `restoreAll` ignores the enabled flags.

### ADB / shell rollback (user executes)

```powershell
adb shell pm enable --user 0 com.hihonor.regservice
adb shell pm enable --user 0 com.hihonor.android.UEInfoCheck
adb shell pm enable --user 0 com.hihonor.android.AutoRegSms
adb shell pm unsuspend --user 0 com.hihonor.hiview
adb shell pm unsuspend --user 0 com.hihonor.hiviewpush
adb shell pm unsuspend --user 0 com.hihonor.hiviewtunnel
```

Verify:

```powershell
adb shell pm list packages -d | Select-String 'com.hihonor.regservice|com.hihonor.android.UEInfoCheck|com.hihonor.android.AutoRegSms'
adb shell dumpsys package com.hihonor.regservice | Select-String 'User 0:'
adb shell dumpsys package com.hihonor.hiview | Select-String 'User 0:'
```

Expected: the trio is absent from `pm list packages -d` and shows `enabled=0`; hiview shows `suspended=false`.

If `adb shell` is denied, use a Shizuku-authorized terminal or the KSU/root terminal with the same `pm` commands. If a package was removed by some other tool (not Nemu), use `pm install-existing --user 0 com.hihonor.regservice` as external recovery (substitute the affected package name); Nemu itself never uninstalls.

### Emergency fallback

If the phone boots but Nemu cannot reach a backend, use Android Settings -> Apps -> Show system apps -> select the component -> Enable. For hiview, use the same `pm unsuspend` from any privileged terminal. Do not factory-reset before trying these.

---

## Risks

| Risk | Impact | Probability | Mitigation |
|---|---|---|---|
| Disabling carrier DM breaks VoLTE/VoWiFi provisioning or carrier config | Medium | Medium | Per-target confirmation; test one package at a time; `pm enable` rollback + reboot; re-enable permanently if needed |
| Disabling `AutoRegSms` blocks carrier self-registration SMS | Low/Medium | Medium | Documented in the warning; rollback restores it |
| Disabling `regservice` hides carrier "system messages" | Low | High (expected) | Documented; this is the intended trade-off |
| Carrier FOTA/remote management stops working while disabled | Medium | High | Documented; re-enable temporarily when carrier service is needed |
| hiview suspension affects diagnostics/logs or is undone by system bindings | Low/Medium | Medium | `suspend` is reversible; force-stop is best-effort; status shows the real flag |
| hiview suspend survives `adb reboot`/crash (safety path skipped) | Low | Medium | Normal shutdown/reboot clears it via `SafetyTargets`; avoid using `adb reboot` in acceptance; manual rollback available |
| System update or carrier provisioning re-enables a component | Medium | Medium | Status readback + periodic re-check; re-apply policy |
| Nemu uninstalled or app data cleared while trio disabled | Medium | Low | Disabled state persists; `restoreAll` does not need toggles; ADB/settings rollback documented |
| User misses that Dry run is ON and believes rollback ran | Medium | Medium | Cloud header shows "Dry run：只记录命令，不执行"; rollback instructions say turn it off |
| `PackageManager` status read throws for a hidden package | Low | Low | `runCatching` -> "未知（读取失败或未安装）"; manifest queries cover all targets; `QUERY_ALL_PACKAGES` declared |
| Accidentally adding a new `DISABLE_USER` target later | High | Low | `CloudControllerTest` asserts the allowlist is exactly the trio; controller fail-closed |
| Work profile/secondary users unaffected | Low | Low | Documented `--user 0` only; this device is single-user |

---

## Acceptance Criteria

- [ ] `CloudTargets` contains exactly the carrier DM trio (`DISABLE_USER`), hiview trio (`SUSPEND`), and `cipservice` (`OBSERVE_ONLY`); it does not contain `pushagent` or any core target.
- [ ] `CloudTargets.disableUserAllowed` is exactly the carrier DM trio; `suspendPackageNames` is exactly the hiview trio.
- [ ] `CloudController` refuses: unconfirmed disable-user, disable-user outside the allowlist, and any `DenyList` package. Unit tests prove each refusal with zero backend calls.
- [ ] `CloudController.apply` produces exactly `pm disable-user --user 0 'com.hihonor.regservice'` (one command per trio package) and `pm suspend` then `am force-stop` for hiview; `restoreAll` produces `pm enable` and `pm unsuspend` and skips `cipservice`.
- [ ] Dry run produces no backend calls for both apply and restore; the cloud UI displays the dry-run state.
- [ ] `SafetyTargets.suspendPackageNames` includes all core targets and the hiview trio, excludes the carrier DM trio, and has no duplicates.
- [ ] `ShutdownSafety` and `BootWorker` use `SafetyTargets.suspendPackageNames`.
- [ ] The cloud UI is a separate section with warning, per-target toggles, disable-user confirmation dialog, policy status, live package status, apply, refresh, and "恢复全部云控".
- [ ] `docs/04-test-plan.md` contains CT1-CT5 with exact commands and expected observations; device results are recorded only after the user executes them (unexecuted = "未测").
- [ ] `README.md`, `docs/03-design.md`, and `docs/02-package-inventory.md` match the implementation, including the narrow disable-user exception.
- [ ] `grep -rni "pm uninstall" app/src` returns no matches.
- [ ] All unit tests pass (expected 51) and `:app:assembleDebug` succeeds.
- [ ] No device write is performed by the implementer; installation and CT1-CT5 are user-executed.

---

## Self-Review Notes (already applied)

- Every requirement from the scope maps to a task: trio disable-user (Tasks 1-2, 4), hiview suspend (Tasks 1-3), observe-only (Tasks 1, 4), separate UI (Task 4), settings isolation (Task 2), tests (Tasks 1-3), docs (Task 5), device plan (Task 5-6), rollback and risks in their own sections.
- Type names are consistent across tasks: `CloudAction`, `CloudTarget`, `CloudTargets`, `CloudStatus`, `CloudSettingsStore`, `CloudSettings`, `CloudController.apply/restoreAll`, `SafetyTargets.suspendPackageNames`, `SafetyUnfreezer.unsuspendPackages`, `PackageStatusReader`, `CloudUiState`.
- No `pm uninstall` path exists in any snippet.
- In this module, `pm disable-user` appears only in `CloudController.commandsFor`, behind `CloudTargets.disableUserAllowed` and `CloudSettings.isConfirmed`.
- `CloudSettings` uses `cloud_`-prefixed keys; core `Settings` is untouched, so `target_*` and `cloud_target_*` cannot collide.
- The status text is driven by real `PackageManager` readback; no UI copy promises cloud-traffic blocking, FOTA immunity, or uninstall.
