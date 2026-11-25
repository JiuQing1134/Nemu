package com.nemu.hibernate.policy

import android.content.Context
import android.content.pm.PackageManager
import com.nemu.hibernate.core.DeepBackupInfo
import kotlinx.coroutines.delay
import com.nemu.hibernate.core.DeepOperationResult
import com.nemu.hibernate.core.DeepUninstallItem
import com.nemu.hibernate.core.DeepUninstallUiState
import com.nemu.hibernate.core.ShellQuote
import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.PrivilegeBackend
import com.nemu.hibernate.ui.AllAppEntry
import com.nemu.hibernate.ui.AppKind
import com.nemu.hibernate.ui.classifyApp

/**
 * Canta-style user-level deep uninstall.
 *
 * Back up every base/split APK, persist a manifest entry, then run
 * `pm uninstall -k --user 0` (keep user data). Restore prefers
 * `pm install-existing --user 0` and falls back to `pm install -r -d --user 0
 * <apk...>` from the verified backup (MagicOS 10 has no install-multiple).
 * Every command goes through [backend]; path arguments are shell-quoted.
 * Public methods never throw.
 */
/** The v1.20 marker, or any later module build, advertises the boot bridge. */
internal fun isDeepRestoreReadyVersion(raw: String): Boolean =
    raw.lineSequence()
        .map { it.trim() }
        .any { line ->
            val parts = line.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()
            major != null && minor != null &&
                (major > 1 || (major == 1 && minor >= 20))
        }

class DeepUninstallService(
    private val backend: PrivilegeBackend,
    private val context: Context
) {
    private data class ManifestState(val text: String, val error: String?)

    /**
     * Merges the installed app list with manifest backups. Packages that
     * exist only in the manifest are returned with `installed = false`.
     */
    internal suspend fun loadItems(installed: List<AllAppEntry>): DeepUninstallUiState {
        return try {
            val manifest = readManifest()
            val backups = if (manifest.error == null) {
                runCatching { DeepUninstallManifest.decodeAll(manifest.text) }
                    .getOrElse {
                        ActionLog.warn("深度卸载 备份清单解析失败: ${it.message}")
                        emptyList()
                    }
            } else {
                emptyList()
            }
            val latestByPackage = mutableMapOf<String, DeepBackupInfo>()
            for (backup in backups) {
                val current = latestByPackage[backup.packageName]
                if (current == null || backup.timestampMillis > current.timestampMillis) {
                    latestByPackage[backup.packageName] = backup
                }
            }

            val items = ArrayList<DeepUninstallItem>(
                installed.size + latestByPackage.size
            )
            val seen = HashSet<String>(installed.size)
            for (app in installed) {
                if (!seen.add(app.packageName)) continue
                items += installedItem(app, latestByPackage[app.packageName])
            }
            latestByPackage.values
                .filter { it.packageName !in seen }
                .sortedByDescending { it.timestampMillis }
                .forEach { items += backupOnlyItem(it) }

            DeepUninstallUiState(items = items, error = manifest.error)
        } catch (t: Throwable) {
            ActionLog.error("深度卸载 列表读取失败: ${t.message}")
            val fallback = runCatching {
                installed.distinctBy { it.packageName }.map { installedItem(it, null) }
            }.getOrDefault(emptyList())
            DeepUninstallUiState(items = fallback, error = "读取备份清单失败")
        }
    }

    suspend fun loadBackups(): List<DeepBackupInfo> {
        val manifest = readManifest()
        if (manifest.error != null) {
            ActionLog.warn("深度卸载 ${manifest.error}")
            return emptyList()
        }
        return runCatching { DeepUninstallManifest.decodeAll(manifest.text) }
            .getOrElse {
                ActionLog.warn("深度卸载 备份清单解析失败: ${it.message}")
                emptyList()
            }
            .sortedByDescending { it.timestampMillis }
    }

    /**
     * True when module v1.20+ has published the deep-restore capability file.
     * The file is versioned so an old module or a stale marker left after an
     * uninstall is not mistaken for the real boot bridge.
     */
    suspend fun bootRestoreReady(): Boolean {
        // A stale marker must not advertise auto restore when the module was
        // disabled by the boot guard or by the module manager.
        val module = exec(DeepUninstallCommands.moduleActive())
        if (!module.ok || module.indeterminate) return false
        val result = exec(
            DeepUninstallCommands.readFile(DeepUninstallManifest.READY_PATH)
        )
        if (!result.ok || result.indeterminate) return false
        return isDeepRestoreReadyVersion(result.output)
    }

    suspend fun backupAndUninstall(
        packageName: String,
        label: String,
        versionCode: Long,
        versionName: String?,
        systemApp: Boolean
    ): DeepOperationResult {
        return runCatching {
            if (!DeepUninstallPolicy.isValidPackageName(packageName)) {
                return@runCatching failure("备份失败：包名无效")
            }
            if (isSelfPackage(packageName)) {
                return@runCatching failure("拒绝操作：不能卸载 Nemu 自身")
            }
            val display = if (label.isBlank()) packageName else label

            val pathResult = exec(DeepUninstallCommands.path(packageName))
            logRaw("pm path $packageName", pathResult)
            if (!pathResult.ok) {
                return@runCatching failure("备份失败：读取安装路径失败")
            }
            val sources = DeepUninstallCommands.parsePmPaths(pathResult.output)
            if (sources.isEmpty()) {
                return@runCatching failure("备份失败：找不到安装包")
            }

            val timestamp = System.currentTimeMillis()
            val safeVersionCode = versionCode.coerceAtLeast(0L)
            val dir = "${DeepUninstallManifest.APK_ROOT}/$packageName/" +
                "$safeVersionCode-$timestamp"
            val downloadDir = DeepUninstallManifest.downloadDir(dir)
            val mkdir = exec(DeepUninstallCommands.makeDirs(dir))
            logRaw("创建备份目录", mkdir)
            if (!mkdir.ok) {
                return@runCatching failure("备份失败：无法创建备份目录")
            }
            val mirrorMkdir = exec(DeepUninstallCommands.makeDirs(downloadDir))
            logRaw("创建 Download 备份目录", mirrorMkdir)
            if (!mirrorMkdir.ok) {
                cleanupBackupDir(dir)
                return@runCatching failure("备份失败：无法创建 Download 备份目录")
            }

            val files = ArrayList<String>(sources.size)
            for ((index, source) in sources.withIndex()) {
                val fileName = DeepUninstallCommands.backupFileName(index)
                val destination = "$dir/$fileName"
                val mirrorDestination = "$downloadDir/$fileName"
                val copy = exec(DeepUninstallCommands.copyFile(source, destination))
                logRaw("复制 $fileName", copy)
                if (!copy.ok) {
                    cleanupBackupDirs(dir, downloadDir)
                    return@runCatching failure("备份失败：复制 APK 失败")
                }
                val hash = exec(DeepUninstallCommands.sha256(destination))
                logRaw("校验 $fileName", hash)
                if (!hash.ok || !DeepUninstallCommands.isSha256Output(hash.output)) {
                    cleanupBackupDirs(dir, downloadDir)
                    return@runCatching failure("备份失败：APK 校验失败")
                }
                val mirrorCopy = exec(
                    DeepUninstallCommands.copyFile(source, mirrorDestination)
                )
                logRaw("复制到 Download $fileName", mirrorCopy)
                if (!mirrorCopy.ok) {
                    cleanupBackupDirs(dir, downloadDir)
                    return@runCatching failure("备份失败：复制到 Download 失败")
                }
                val mirrorHash = exec(
                    DeepUninstallCommands.sha256(mirrorDestination)
                )
                logRaw("校验 Download $fileName", mirrorHash)
                if (!mirrorHash.ok ||
                    !DeepUninstallCommands.isSha256Output(mirrorHash.output)
                ) {
                    cleanupBackupDirs(dir, downloadDir)
                    return@runCatching failure("备份失败：Download APK 校验失败")
                }
                files += fileName
            }
            if (files.isEmpty()) {
                cleanupBackupDirs(dir, downloadDir)
                return@runCatching failure("备份失败：找不到安装包")
            }

            val info = DeepBackupInfo(
                packageName = packageName,
                label = label,
                versionCode = versionCode,
                versionName = versionName,
                dir = dir,
                files = files,
                timestampMillis = timestamp,
                systemApp = systemApp
            )
            val manifest = readManifest()
            if (manifest.error != null) {
                cleanupBackupDirs(dir, downloadDir)
                return@runCatching failure("备份失败：无法读取备份清单")
            }
            val entries = DeepUninstallManifest.replace(manifest.text.lines(), info)
            val write = exec(
                DeepUninstallCommands.writeManifest(
                    DeepUninstallManifest.encodeAll(entries)
                )
            )
            logRaw("写入备份清单", write)
            if (!write.ok) {
                cleanupBackupDirs(dir, downloadDir)
                return@runCatching failure("备份失败：无法写入备份清单")
            }
            // Keep a manifest snapshot beside the Download APKs too. The
            // primary directory remains the restore source; this copy makes
            // a manual transfer self-describing.
            val mirrorManifest = exec(
                DeepUninstallCommands.copyFileReadable(
                    DeepUninstallManifest.MANIFEST_PATH,
                    DeepUninstallManifest.DOWNLOAD_MANIFEST_PATH
                )
            )
            logRaw("同步 Download 备份清单", mirrorManifest)
            if (!mirrorManifest.ok) {
                cleanupBackupDir(downloadDir)
                return@runCatching failure(
                    "备份失败：无法写入 Download 清单（主备份已保留）"
                )
            }

            // The APK set and manifest are durable before the package is
            // removed, so a failed uninstall always keeps a restore path.
            val uninstall = exec(DeepUninstallCommands.uninstallForUser(packageName))
            logRaw("pm uninstall -k --user 0 $packageName", uninstall)
            if (!uninstall.ok) {
                val message =
                    if (uninstall.output.contains("not installed", ignoreCase = true)) {
                        "卸载失败：该应用当前未安装（备份已保留）"
                    } else {
                        "卸载失败：系统拒绝卸载（备份已保留）"
                    }
                return@runCatching failure(message)
            }
            when (isInstalledForUser(packageName)) {
                false -> Unit
                true -> return@runCatching failure("卸载失败：卸载后应用仍存在（备份已保留）")
                null -> return@runCatching failure("卸载失败：无法确认卸载结果（备份已保留）")
            }

            // The package is gone for user 0. Drop Nemu's freeze ownership
            // and boot-plan bit now, otherwise the freeze/unfreeze pages keep
            // a "待确认" row and a later restore would look frozen.
            TargetCatalog.all.firstOrNull { it.packageName == packageName }
                ?.let { target ->
                    if (!TargetPolicy(context).clearOwnership(target)) {
                        ActionLog.warn("深度卸载：$packageName 的冻结标记清理未完全成功")
                    }
                }
            ActionLog.info(
                "深度卸载：$packageName 已备份到 $dir 和 $downloadDir 并从用户 0 卸载"
            )
            DeepOperationResult(
                true,
                "已卸载「$display」，备份在 Download/Nemu"
            )
        }.getOrElse { t ->
            ActionLog.error("深度卸载 备份/卸载异常: ${t.message}")
            failure("备份失败：${t.message ?: "未知错误"}")
        }
    }

    /**
     * Restores a package from `install-existing` or the APK backup. The
     * manifest entry and APK backup are intentionally kept: the app becomes
     * "已恢复 · 保留备份", the module skips it at boot because it is already
     * installed, and the backup is ready for the next deep-uninstall cycle.
     */
    suspend fun restore(packageName: String): DeepOperationResult {
        return runCatching {
            if (!DeepUninstallPolicy.isValidPackageName(packageName)) {
                return@runCatching failure("恢复失败：包名无效")
            }

            var installExisting =
                exec(DeepUninstallCommands.installExisting(packageName))
            logRaw("pm install-existing --user 0 $packageName", installExisting)
            // A transient binder/service failure does not prove the package
            // is gone. Retry once unless the answer is "doesn't exist";
            // only that answer makes a backup install safe.
            if (!installExisting.ok &&
                !installExisting.output.contains("doesn't exist", true) &&
                !installExisting.output.contains("NameNotFoundException", true)
            ) {
                delay(1_000)
                installExisting =
                    exec(DeepUninstallCommands.installExisting(packageName))
                logRaw("重试 pm install-existing $packageName", installExisting)
            }
            if (installExisting.ok) {
                // install-existing sets the user state, but MagicOS delapp
                // preloads keep no APK (pm path empty) until the next boot
                // rescan. Report a live restore only when the code is
                // actually present; otherwise ask for a reboot instead of
                // installing the backup (that crashed PackageManager on a
                // null AndroidPackage).
                var published = isInstalledForUser(packageName) == true
                var wait = 0
                while (!published && wait < 15) {
                    delay(400)
                    published = isInstalledForUser(packageName) == true
                    wait++
                }
                val pathResult = exec(DeepUninstallCommands.path(packageName))
                val hasCode = pathResult.ok &&
                    DeepUninstallCommands.parsePmPaths(pathResult.output)
                        .isNotEmpty()
                val enable = exec(
                    DeepUninstallCommands.enableForUser(packageName)
                )
                logRaw("pm enable --user 0 $packageName", enable)
                return@runCatching if (published || hasCode) {
                    ActionLog.info(
                        "深度卸载：$packageName 已通过 install-existing 恢复"
                    )
                    DeepOperationResult(true, "已恢复「$packageName」")
                } else {
                    ActionLog.info(
                        "深度卸载：$packageName install-existing 已登记，" +
                            "等待重启后系统恢复（预装 delapp）"
                    )
                    DeepOperationResult(
                        true,
                        "已登记恢复「$packageName」，重启后生效"
                    )
                }
            }

            // MagicOS keeps a delapp/preload record after uninstalling such
            // a system app. Installing a backup over that record crashes
            // PackageManager, so only a truly removed package (no record)
            // may be restored from the APK.
            val record = exec(
                DeepUninstallCommands.listPackagesIncludingUninstalled(
                    packageName
                )
            )
            if (record.ok && !record.indeterminate &&
                DeepUninstallCommands.listsPackage(record.output, packageName)
            ) {
                ActionLog.warn(
                    "深度卸载：$packageName 仍保留系统记录，跳过备份重装（需重启）"
                )
                return@runCatching failure(
                    "恢复失败：系统保留了该应用记录，重启后再试"
                )
            }

            val manifest = readManifest()
            val backups = if (manifest.error == null) {
                runCatching { DeepUninstallManifest.decodeAll(manifest.text) }
                    .getOrElse { emptyList<DeepBackupInfo>() }
            } else {
                emptyList<DeepBackupInfo>()
            }
            val backup = backups
                .filter { it.packageName == packageName }
                .maxByOrNull { it.timestampMillis }
            if (backup == null) {
                return@runCatching failure("恢复失败：没有可用备份")
            }
            if (!backup.dir.startsWith(DeepUninstallManifest.APK_ROOT + "/")) {
                return@runCatching failure("恢复失败：备份路径无效")
            }
            if (backup.files.isEmpty()) {
                return@runCatching failure("恢复失败：没有可用备份")
            }
            val paths = backup.files.map { "${backup.dir}/$it" }
            for (path in paths) {
                val exists = exec(DeepUninstallCommands.testFile(path))
                if (!exists.ok) {
                    logRaw("检查备份文件", exists)
                    return@runCatching failure("恢复失败：备份文件缺失")
                }
            }

            // PackageInstaller (system_server) cannot read adb_data_file
            // under /data/adb, so stage world-readable copies under
            // /data/local/tmp (shell_data_file) for the install.
            val stageDir = DeepUninstallCommands.stageDir(packageName)
            exec(DeepUninstallCommands.removeDir(stageDir))
            val mkdir = exec(DeepUninstallCommands.makeDirs(stageDir))
            logRaw("创建恢复暂存目录", mkdir)
            if (!mkdir.ok) {
                return@runCatching failure("恢复失败：无法创建暂存目录")
            }
            val stagedPaths = backup.files.map {
                "$stageDir/${DeepUninstallCommands.installFileName(it)}"
            }
            for ((index, source) in paths.withIndex()) {
                val copy = exec(
                    DeepUninstallCommands.copyFileReadable(
                        source,
                        stagedPaths[index]
                    )
                )
                logRaw("暂存 ${backup.files[index]}", copy)
                if (!copy.ok) {
                    exec(DeepUninstallCommands.removeDir(stageDir))
                    return@runCatching failure("恢复失败：无法暂存备份 APK")
                }
            }

            val install = try {
                exec(DeepUninstallCommands.installMultiple(stagedPaths))
            } finally {
                exec(DeepUninstallCommands.removeDir(stageDir))
            }
            logRaw("pm install $packageName", install)
            if (!install.ok) {
                return@runCatching failure("恢复失败：备份 APK 安装失败")
            }
            if (isInstalledForUser(packageName) != true) {
                return@runCatching failure("恢复失败：安装后未检测到应用")
            }

            val enable = exec(DeepUninstallCommands.enableForUser(packageName))
            logRaw("pm enable --user 0 $packageName", enable)
            ActionLog.info("深度卸载：$packageName 已从备份 ${backup.dir} 恢复")
            val display = if (backup.label.isBlank()) packageName else backup.label
            DeepOperationResult(true, "已从备份恢复「$display」")
        }.getOrElse { t ->
            ActionLog.error("深度卸载 恢复异常: ${t.message}")
            failure("恢复失败：${t.message ?: "未知错误"}")
        }
    }

    private suspend fun readManifest(): ManifestState {
        val result = exec(
            DeepUninstallCommands.readFile(DeepUninstallManifest.MANIFEST_PATH)
        )
        if (result.ok && !result.indeterminate) {
            return ManifestState(result.output, null)
        }
        logRaw("读取备份清单", result)
        if (result.indeterminate) {
            return ManifestState("", "读取备份清单超时")
        }
        val missing = result.output.contains("No such file", ignoreCase = true) ||
            result.output.contains("not found", ignoreCase = true)
        if (missing) return ManifestState("", null)
        return ManifestState("", "读取备份清单失败（退出码 ${result.exitCode}）")
    }

    private suspend fun isInstalledForUser(packageName: String): Boolean? {
        val result = exec(DeepUninstallCommands.listPackagesForUser(packageName))
        logRaw("pm list packages --user 0 $packageName", result)
        if (!result.ok || result.indeterminate) return null
        return DeepUninstallCommands.listsPackage(result.output, packageName)
    }

    private suspend fun cleanupBackupDir(dir: String) {
        val result = exec(DeepUninstallCommands.removeDir(dir))
        logRaw("清理本次备份目录", result)
    }

    /** Remove both the /data/adb and Download copies after a failed backup. */
    private suspend fun cleanupBackupDirs(primary: String, mirror: String) {
        cleanupBackupDir(primary)
        if (mirror != primary) cleanupBackupDir(mirror)
    }

    private suspend fun exec(command: String): ShellResult =
        runCatching { backend.exec(command) }
            .getOrElse {
                ShellResult(exitCode = -1, output = it.message ?: "命令执行失败")
            }

    private fun logRaw(tag: String, result: ShellResult) {
        val detail = result.output.trim().replace('\n', ' ').take(600)
        if (result.ok) {
            ActionLog.info("深度卸载 $tag: exit=0 $detail")
        } else {
            val timeout = if (result.indeterminate) " 超时" else ""
            ActionLog.warn("深度卸载 $tag: exit=${result.exitCode}$timeout $detail")
        }
    }

    private fun installedItem(
        app: AllAppEntry,
        backup: DeepBackupInfo?
    ): DeepUninstallItem {
        val label = if (app.label.isBlank()) {
            backup?.label?.takeIf { it.isNotBlank() } ?: app.packageName
        } else {
            app.label
        }
        return DeepUninstallItem(
            packageName = app.packageName,
            label = label,
            versionName = installedVersionName(app.packageName) ?: backup?.versionName,
            installed = true,
            systemApp = DeepUninstallPolicy.isSystemApp(app.kind),
            kindLabel = app.kind.label,
            systemCategory = app.kind == AppKind.SYSTEM,
            backup = backup,
            critical = DeepUninstallPolicy.isCritical(
                app.packageName,
                app.label,
                app.kind,
                app.criticalReason
            )
        )
    }

    private fun backupOnlyItem(backup: DeepBackupInfo): DeepUninstallItem {
        // Classify by package name so a backup-only Root manager / Shizuku /
        // LSPosed entry keeps the same category chip as the All Apps list.
        val kind = classifyApp(
            backup.packageName,
            backup.label,
            backup.systemApp
        )
        val label = if (backup.label.isBlank()) backup.packageName else backup.label
        return DeepUninstallItem(
            packageName = backup.packageName,
            label = label,
            versionName = backup.versionName,
            installed = false,
            systemApp = backup.systemApp,
            kindLabel = kind.label,
            systemCategory = kind == AppKind.SYSTEM,
            backup = backup,
            critical = DeepUninstallPolicy.isCritical(
                backup.packageName,
                backup.label,
                kind,
                null
            )
        )
    }

    @Suppress("DEPRECATION") // The Int overload also works below API 33.
    private fun installedVersionName(packageName: String): String? =
        runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            ).versionName
        }.getOrNull()

    private fun isSelfPackage(packageName: String): Boolean {
        if (DeepUninstallPolicy.isSelf(packageName)) return true
        return runCatching { packageName == context.packageName }
            .getOrDefault(false)
    }

    private fun failure(message: String) = DeepOperationResult(false, message)
}

/**
 * Pure command builders/parsers. Extracted so the exact `pm` invocations and
 * output handling can be unit-tested without a device.
 */
internal object DeepUninstallCommands {
    private val sha256Line = Regex("^[0-9a-fA-F]{64}(?:\\s|$)")

    private fun q(value: String): String = ShellQuote.quote(value)

    fun path(packageName: String): String = "pm path ${q(packageName)}"

    fun uninstallForUser(packageName: String): String =
        "pm uninstall -k --user 0 ${q(packageName)}"

    fun listPackagesForUser(packageName: String): String =
        "pm list packages --user 0 ${q(packageName)}"

    /**
     * Includes packages that are uninstalled for the user. MagicOS keeps a
     * delapp/preload record after `pm uninstall -k --user 0`; installing a
     * backup over that record crashes PackageManager (null AndroidPackage),
     * so this gates the backup fallback.
     */
    fun listPackagesIncludingUninstalled(packageName: String): String =
        "pm list packages -u --user 0 ${q(packageName)}"

    fun installExisting(packageName: String): String =
        "pm install-existing --user 0 ${q(packageName)}"

    /**
     * PackageInstaller runs in system_server, which is denied reading the
     * adb_data_file backups under /data/adb. Files copied here are
     * shell_data_file, which system_server can read.
     */
    fun stageDir(packageName: String): String =
        "/data/local/tmp/nemu_deep_install/$packageName"

    /**
     * Stored APKs use a .bak suffix so system cleaners / APK scanners leave
     * them alone; the manifest records these physical names.
     */
    fun backupFileName(index: Int): String =
        (if (index == 0) "base.apk" else "split_${index - 1}.apk") + ".bak"

    /** Install-time name: strip .bak; legacy .apk names pass through. */
    fun installFileName(backupFileName: String): String =
        backupFileName.removeSuffix(".bak")

    fun enableForUser(packageName: String): String =
        "pm enable --user 0 ${q(packageName)}"

    /**
     * Succeeds only when the v1.20+ bridge is in the active module directory
     * and the module is neither disabled nor marked for removal.
     */
    fun moduleActive(): String {
        val root = DeepUninstallManifest.MODULE_DIR
        return "test -f ${q("$root/deep_restore.sh")} && " +
            "test ! -e ${q("$root/disable")} && " +
            "test ! -e ${q("$root/remove")}"
    }

    /**
     * MagicOS 10 removed `pm install-multiple` ("Unknown command"), so the
     * fallback is `pm install` with one or more APK paths; it runs the same
     * session-based install for multi-APK backups.
     */
    fun installMultiple(paths: List<String>): String = buildString {
        append("pm install -r -d --user 0")
        for (path in paths) {
            append(' ').append(q(path))
        }
    }

    fun sha256(path: String): String = "sha256sum ${q(path)}"

    fun copyFile(source: String, destination: String): String =
        "cp -f ${q(source)} ${q(destination)}"

    /** Copy for the user-visible Download mirror; keep it plainly readable. */
    fun copyFileReadable(source: String, destination: String): String =
        "cp -f ${q(source)} ${q(destination)} && chmod 644 ${q(destination)}"

    fun makeDirs(dir: String): String = "mkdir -p ${q(dir)}"

    fun removeDir(dir: String): String = "rm -rf ${q(dir)}"

    fun testFile(path: String): String = "test -f ${q(path)}"

    fun readFile(path: String): String = "cat ${q(path)}"

    /**
     * Atomic manifest replace: write a sibling temp file, lock it down, then
     * rename it over the manifest (same filesystem, so the rename is atomic
     * from the reader's point of view).
     */
    fun writeManifest(text: String): String {
        val target = DeepUninstallManifest.MANIFEST_PATH
        val temp = "$target.tmp"
        val payload = if (text.isEmpty() || text.endsWith('\n')) text else "$text\n"
        return "mkdir -p ${q(DeepUninstallManifest.ROOT)} && " +
            "printf %s ${q(payload)} > ${q(temp)} && " +
            "chmod 600 ${q(temp)} && mv -f ${q(temp)} ${q(target)}"
    }

    /**
     * Parses `pm path <pkg>` output. Each APK is on a `package:<path>` line.
     * The base APK is moved to the front so the manifest's first file is
     * always the base; split order is otherwise preserved.
     */
    fun parsePmPaths(output: String): List<String> {
        val paths = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return paths.sortedWith(compareBy { !it.endsWith("/base.apk") })
    }

    /** `sha256sum` prints `<64 hex>  <path>`; a non-empty hash is required. */
    fun isSha256Output(output: String): Boolean =
        output.lineSequence().any { sha256Line.containsMatchIn(it.trim()) }

    /**
     * `pm list packages --user 0 <filter>` filters by substring and prints
     * `package:<pkg>`, so each line must be compared with the exact package
     * name to avoid matching `com.example.app2` for `com.example.app`.
     */
    fun listsPackage(output: String, packageName: String): Boolean =
        output.lineSequence().any { line ->
            line.trim().removePrefix("package:").trim() == packageName
        }
}

/**
 * Critical-app and identifier rules for deep uninstall.
 *
 * "Critical" only raises the confirmation bar; Nemu itself is the one
 * package that is hard-rejected by [DeepUninstallService].
 */
internal object DeepUninstallPolicy {
    private val selfPackages = setOf(
        "com.nemu.dream",
        "com.nemu.hibernate"
    )

    private val criticalPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.hihonor.android.launcher",
        "com.hihonor.launcher",
        "com.hihonor.systemserver",
        "com.hihonor.systemmanager",
        "com.hihonor.powergenie",
        "com.hihonor.securityserver",
        "com.hihonor.awareness",
        "com.hihonor.iaware",
        "com.hihonor.contacts",
        "com.hihonor.dialer",
        "com.hihonor.phone",
        "com.hihonor.telecom",
        "com.hihonor.incallui",
        "com.nemu.dream",
        "com.nemu.hibernate",
        "com.nemu.windstop",
        "me.yuki.fina",
        "moe.shizuku.privileged.api",
        "roro.stellar.manager",
        "org.lsposed.manager",
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
        "com.rifsxd.ksunext",
        "com.sukisu.ultra",
        "me.bmax.apatch"
    )

    private val criticalPrefixes = listOf(
        "com.android.launcher",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.internal.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.hihonor.launcher",
        "com.hihonor.android.launcher",
        "com.google.android.apps.nexuslauncher"
    )

    private val criticalPackageTokens = listOf(
        "systemui",
        "packageinstaller",
        "permissioncontroller",
        "launcher",
        "settings",
        "telecom",
        "dialer",
        "incallui"
    )

    private val criticalLabelTokens = listOf(
        "system ui",
        "systemui",
        "package installer",
        "permission controller",
        "launcher",
        "拨号",
        "启动器",
        "桌面",
        "系统界面",
        "系统桌面",
        "软件包安装",
        "权限控制"
    )

    private val rootTokens = listOf(
        "kernelsu",
        "magisk",
        "ksunext",
        "sukisu",
        "apatch"
    )

    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    fun isSelf(packageName: String): Boolean = packageName in selfPackages

    fun isSystemApp(kind: AppKind): Boolean = kind != AppKind.USER

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 1..255 && packageNamePattern.matches(packageName)

    fun isCritical(
        packageName: String,
        label: String,
        kind: AppKind,
        criticalReason: String?
    ): Boolean {
        if (isSelf(packageName)) return true
        if (!criticalReason.isNullOrBlank()) return true
        when (kind) {
            AppKind.ROOT,
            AppKind.SHIZUKU,
            AppKind.LSPOSED -> return true
            else -> Unit
        }

        val pkg = packageName.lowercase()
        val text = label.lowercase()
        if (pkg in criticalPackages) return true
        if (criticalPrefixes.any { pkg.startsWith(it) }) return true
        if (criticalPackageTokens.any { pkg.contains(it) }) return true
        if (rootTokens.any { pkg.contains(it) || text.contains(it) }) return true
        if (criticalLabelTokens.any { text.contains(it) }) return true
        return false
    }
}
