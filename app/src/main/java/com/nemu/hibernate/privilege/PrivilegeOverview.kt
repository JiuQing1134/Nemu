package com.nemu.hibernate.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Read-only snapshot of what Nemu is currently allowed to do.
 *
 * Every field is a plain value so the UI can hold it in state and render it
 * without touching Android APIs again. All explanatory text is written for a
 * user who does not know what "su", "uid 2000" or "wireless debugging" mean:
 * the dedicated label helpers below turn the raw flags into full sentences.
 */
data class PrivilegeOverview(
    /** True when `su` works and returns uid=0. */
    val rootAvailable: Boolean,
    /** Full sentence describing the root state. */
    val rootDetail: String,
    /** True when the Shizuku app package is installed. */
    val shizukuInstalled: Boolean,
    /** True when the Shizuku service binder answers. */
    val shizukuRunning: Boolean,
    /** True only while Shizuku is running and Nemu holds its permission. */
    val shizukuGranted: Boolean,
    /** UID Shizuku runs as: 0 = root-started, 2000 = ADB/shell-started, null = unknown. */
    val shizukuUid: Int?,
    /** `Settings.Global.ADB_ENABLED` — USB debugging switch. */
    val adbEnabled: Boolean,
    /** `Settings.Global.adb_wifi_enabled` — wireless debugging switch. */
    val wirelessDebuggingEnabled: Boolean,
    /** Human-readable name of the strongest usable backend, or "未授权". */
    val activeBackendLabel: String,
    /** 2–3 plain-language sentences: what is authorized today and what to do next. */
    val plainSummary: String,
) {
    /** 未安装 / 未运行 / 已运行·未授权 / 已运行·ADB 授权 / 已运行·Root 授权 */
    val shizukuModeLabel: String
        get() = PrivilegeLabels.shizukuModeLabel(
            installed = shizukuInstalled,
            running = shizukuRunning,
            granted = shizukuGranted,
            uid = shizukuUid,
        )

    /** "ADB 调试已开启；无线调试已关闭" — covers both switches at once. */
    val adbLabel: String
        get() = PrivilegeLabels.adbLabel(adbEnabled, wirelessDebuggingEnabled)

    /** Whether freeze/unfreeze can run at all right now. */
    val canFreeze: Boolean
        get() = rootAvailable || (shizukuRunning && shizukuGranted)
}

/**
 * Pure Kotlin label builders, separate from Android so they are unit-testable
 * on the JVM. The shell/root distinction comes from Shizuku's UID
 * (0 = server started by root, 2000 = started by ADB/shell).
 */
object PrivilegeLabels {
    const val ROOT_GRANTED = "已授权：Root 权限可用，可以停用组件和执行最强的冻结。"
    const val ROOT_MISSING =
        "未授权（su 不可用）：没有 Root 权限，无法停用系统组件，只能通过 Shizuku 冻结应用。"

    fun rootDetail(rootAvailable: Boolean): String =
        if (rootAvailable) ROOT_GRANTED else ROOT_MISSING

    fun shizukuModeLabel(
        installed: Boolean,
        running: Boolean,
        granted: Boolean,
        uid: Int?,
    ): String = when {
        // Running wins over "installed": without a <queries> entry the package
        // lookup can fail even though the Shizuku binder is alive.
        running && granted && uid == 0 -> "已运行·Root 授权"
        running && granted && uid == 2000 -> "已运行·ADB 授权"
        running && granted -> "已运行·已授权"
        running -> "已运行·未授权"
        installed -> "未运行"
        else -> "未安装"
    }

    fun adbLabel(adbEnabled: Boolean, wirelessDebuggingEnabled: Boolean): String =
        "ADB 调试${if (adbEnabled) "已开启" else "已关闭"}；" +
            "无线调试${if (wirelessDebuggingEnabled) "已开启" else "已关闭"}"

    fun shizukuBackendLabel(uid: Int?): String = when (uid) {
        0 -> "Shizuku（Root 启动）"
        2000 -> "Shizuku（ADB 启动）"
        else -> "Shizuku"
    }

    /**
     * Root wins when both are usable because it is the only path that can
     * disable components; this matches BackendManager's automatic ordering.
     */
    fun activeBackendLabel(
        rootAvailable: Boolean,
        shizukuRunning: Boolean,
        shizukuGranted: Boolean,
        shizukuUid: Int?,
    ): String = when {
        rootAvailable -> "Root"
        shizukuRunning && shizukuGranted -> shizukuBackendLabel(shizukuUid)
        else -> "未授权"
    }

    fun plainSummary(
        rootAvailable: Boolean,
        shizukuInstalled: Boolean,
        shizukuRunning: Boolean,
        shizukuGranted: Boolean,
        shizukuUid: Int?,
        adbEnabled: Boolean,
    ): String {
        if (rootAvailable) {
            return "已经拿到 Root 权限，Nemu 可以正常冻结、解冻应用，" +
                "也能停用系统组件和调整温控策略。Root 的权限最完整，不需要再额外授权。"
        }
        if (shizukuRunning && shizukuGranted) {
            val via = if (shizukuUid == 0) "Root" else "ADB"
            return "已经通过 Shizuku（$via 方式启动）拿到授权，" +
                "Nemu 可以正常冻结和解冻应用。" +
                "Shizuku 的权限比 Root 小：停用系统组件、温控和 OTA 相关操作仍然需要 Root。"
        }
        if (shizukuRunning) {
            return "Shizuku 正在运行，但还没有把权限授予 Nemu，所以现在还不能冻结应用。" +
                "请打开 Shizuku 应用，在“已授权应用”里允许 Nemu，" +
                "然后回到这里点“刷新授权状态”。"
        }
        if (shizukuInstalled) {
            return if (adbEnabled) {
                "已经安装了 Shizuku，但它现在没有运行，所以现在还不能冻结应用。" +
                    "ADB 调试已开启：请打开 Shizuku，用电脑或无线 ADB 启动服务，" +
                    "然后回来给 Nemu 授权。"
            } else {
                "已经安装了 Shizuku，但它现在没有运行，所以现在还不能冻结应用。" +
                    "没有 Root 时，需要先在开发者选项里开启 ADB 调试，" +
                    "用电脑启动 Shizuku，再回来授权。"
            }
        }
        if (adbEnabled) {
            return "目前还没有任何冻结权限，现在还不能冻结应用。" +
                "系统已开启 ADB 调试：可以安装 Shizuku 并用 ADB 启动它，" +
                "或者给手机 Root，拿到权限后再回来。"
        }
        return "还没有任何权限，现在还不能冻结应用。" +
            "Nemu 需要 Root 或 Shizuku 才能冻结应用。" +
            "请先给手机 Root，或开启开发者选项和 ADB 调试后用 Shizuku 授权。"
    }
}

/**
 * Collects the values of [PrivilegeOverview] from the device.
 *
 * The Shizuku package is normally invisible to third-party apps; the caller
 * (the activity) is expected to add a `<queries>` entry for it in the manifest.
 * Without that entry, [PrivilegeOverview.shizukuInstalled] stays false while
 * [PrivilegeOverview.shizukuRunning] can still be true when Shizuku is alive.
 */
object PrivilegeOverviewReader {
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Hidden framework key; there is no public constant for it. */
    private const val SETTING_WIRELESS_ADB = "adb_wifi_enabled"

    /**
     * Lightweight refresh used when the activity resumes. It updates only the
     * Shizuku/ADB fields and keeps the last root result, so returning from
     * Stellar must not trigger another su prompt.
     */
    suspend fun readShizuku(
        context: Context,
        previous: PrivilegeOverview
    ): PrivilegeOverview = withContext(Dispatchers.IO) {
        val shizukuInstalled = isShizukuInstalled(context)
        val shizukuRunning = isShizukuRunning()
        val shizukuGranted = shizukuRunning && isShizukuGranted()
        val shizukuUid = if (shizukuRunning) shizukuUid() else null
        val adbEnabled = readGlobalFlag(context, Settings.Global.ADB_ENABLED)
        val wirelessDebuggingEnabled = readGlobalFlag(context, SETTING_WIRELESS_ADB)
        previous.copy(
            shizukuInstalled = shizukuInstalled,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            shizukuUid = shizukuUid,
            adbEnabled = adbEnabled,
            wirelessDebuggingEnabled = wirelessDebuggingEnabled,
            activeBackendLabel = PrivilegeLabels.activeBackendLabel(
                rootAvailable = previous.rootAvailable,
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
                shizukuUid = shizukuUid,
            ),
            plainSummary = PrivilegeLabels.plainSummary(
                rootAvailable = previous.rootAvailable,
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
                shizukuUid = shizukuUid,
                adbEnabled = adbEnabled,
            ),
        )
    }

    /**
     * Reads every field in one pass. This may trigger the root manager's
     * authorization prompt on first use (through BackendManager's su probe),
     * which is acceptable on an explicit refresh or app launch.
     */
    suspend fun read(context: Context, manager: BackendManager): PrivilegeOverview =
        withContext(Dispatchers.IO) {
            val rootAvailable = manager.rootAvailable()
            val shizukuInstalled = isShizukuInstalled(context)
            val shizukuRunning = isShizukuRunning()
            val shizukuGranted = shizukuRunning && isShizukuGranted()
            val shizukuUid = if (shizukuRunning) shizukuUid() else null
            val adbEnabled = readGlobalFlag(context, Settings.Global.ADB_ENABLED)
            val wirelessDebuggingEnabled = readGlobalFlag(context, SETTING_WIRELESS_ADB)

            PrivilegeOverview(
                rootAvailable = rootAvailable,
                rootDetail = PrivilegeLabels.rootDetail(rootAvailable),
                shizukuInstalled = shizukuInstalled,
                shizukuRunning = shizukuRunning,
                shizukuGranted = shizukuGranted,
                shizukuUid = shizukuUid,
                adbEnabled = adbEnabled,
                wirelessDebuggingEnabled = wirelessDebuggingEnabled,
                activeBackendLabel = PrivilegeLabels.activeBackendLabel(
                    rootAvailable = rootAvailable,
                    shizukuRunning = shizukuRunning,
                    shizukuGranted = shizukuGranted,
                    shizukuUid = shizukuUid,
                ),
                plainSummary = PrivilegeLabels.plainSummary(
                    rootAvailable = rootAvailable,
                    shizukuInstalled = shizukuInstalled,
                    shizukuRunning = shizukuRunning,
                    shizukuGranted = shizukuGranted,
                    shizukuUid = shizukuUid,
                    adbEnabled = adbEnabled,
                ),
            )
        }

    @Suppress("DEPRECATION") // The Int overload is the only one that works below API 33.
    private fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun isShizukuGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun shizukuUid(): Int? = runCatching { Shizuku.getUid() }.getOrNull()

    private fun readGlobalFlag(context: Context, name: String): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, name, 0) == 1
    }.getOrDefault(false)
}
