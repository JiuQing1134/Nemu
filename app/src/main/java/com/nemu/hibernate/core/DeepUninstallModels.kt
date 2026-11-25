package com.nemu.hibernate.core

/** A verified APK backup set for one package. */
data class DeepBackupInfo(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val dir: String,
    /** File names inside [dir], base first. */
    val files: List<String>,
    val timestampMillis: Long,
    val systemApp: Boolean
)

/** One row in the deep-uninstall page. */
data class DeepUninstallItem(
    val packageName: String,
    val label: String,
    val versionName: String? = null,
    val installed: Boolean = true,
    val systemApp: Boolean = false,
    /** Same classification as the All Apps list (系统/用户/Root 管理器/Shizuku/…). */
    val kindLabel: String = if (systemApp) "系统" else "用户",
    /** True only for AppKind.SYSTEM; Root/Shizuku/LSPosed are not "系统". */
    val systemCategory: Boolean = false,
    val backup: DeepBackupInfo? = null,
    /** Launcher/SystemUI/installer-class app; requires the danger confirmation. */
    val critical: Boolean = false
)

data class DeepUninstallUiState(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val items: List<DeepUninstallItem> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** Module v1.20+ early-restore handshake; false when the module is absent/disabled. */
    val bootRestoreReady: Boolean = false
)

data class DeepOperationResult(
    val ok: Boolean,
    val message: String
)
