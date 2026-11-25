package com.nemu.hibernate.cota

import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

class CotaController(
    private val backend: RootPrivilegeBackend?,
    private val enabled: Boolean,
    private val backupRoot: String,
    private val clock: () -> String = { LocalDateTime.now().format(BACKUP_ID_FORMAT) }
) {
    private fun activeBackend(): RootPrivilegeBackend? =
        if (enabled) backend else null

    suspend fun listFiles(): CotaResult<List<CotaFile>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val listing = b.exec(CotaCommandBuilder.listFiles())
        if (!listing.ok) {
            return CotaResult.Error(
                "读取目录失败。请确认已授权 Root，然后点“刷新列表”重试。"
            )
        }
        if (CotaParsers.isDirMissing(listing.output)) {
            return CotaResult.Error("目录不存在：${CotaPaths.SOURCE_DIR}")
        }
        val (names, skipped) = CotaParsers.parseFileNames(listing.output)
        if (skipped > 0) {
            ActionLog.warn("COTA：跳过 $skipped 个无法安全处理的条目")
        }
        if (names.isEmpty()) return CotaResult.Ok(emptyList())

        val paths = names.map { CotaPaths.sourcePath(it) }
        val stats = when (val result = statChunks(b, paths)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        if (stats.keys != names.toSet()) {
            return CotaResult.Error("文件列表在读取过程中发生变化，请刷新")
        }

        val hashes = when (val result = hashChunks(b, paths)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        if (hashes.keys != names.toSet()) {
            return CotaResult.Error("SHA-256 结果不完整，请刷新")
        }

        return CotaResult.Ok(
            names.map { name ->
                val stat = stats.getValue(name)
                CotaFile(
                    name = name,
                    sizeBytes = stat.sizeBytes,
                    sha256 = hashes.getValue(name),
                    mode = stat.mode,
                    uid = stat.uid,
                    gid = stat.gid,
                    kind = CotaKnownFiles.kindOf(name),
                    modifiedAtSeconds = stat.modifiedAtSeconds
                )
            }
        )
    }

    suspend fun backup(names: Set<String>): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (names.isEmpty()) return CotaResult.Error("未选择文件")
        if (names.any { !CotaPaths.isSafeRelativePath(it) }) {
            return CotaResult.Error("包含无法安全处理的文件名")
        }
        val selected = names.sorted()
        val reserved = selected.filter { CotaPaths.isReservedRootName(it) }
        if (reserved.isNotEmpty()) {
            return CotaResult.Error(
                "不能备份根目录下的备份清单文件：${reserved.joinToString()}"
            )
        }

        val current = when (val result = listFiles()) {
            is CotaResult.Ok -> result.value.associateBy { it.name }
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val missing = selected.filter { it !in current }
        if (missing.isNotEmpty()) {
            return CotaResult.Error("文件不存在或已变化：${missing.joinToString()}")
        }

        // A backup that can never be restored is not a backup. Refuse special
        // permission bits before deleting anything from the device.
        val unsafeModes = selected.filter {
            !CotaPaths.isSafeRestoreMode(current.getValue(it).mode)
        }
        if (unsafeModes.isNotEmpty()) {
            return CotaResult.Error(
                "包含不可恢复的特殊权限文件，已拒绝备份：${unsafeModes.joinToString()}"
            )
        }
        val backupId = clock()
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份时间戳无效：$backupId")
        }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        val copy = b.exec(CotaCommandBuilder.backup(backupRoot, backupId, selected))
        if (!copy.ok) {
            return CotaResult.Error(
                "备份失败。请重试；若仍失败，请检查 Root 授权和存储空间。"
            )
        }

        val copiedHashes = when (
            val result = hashChunks(
                b,
                selected.map { CotaPaths.backupPath(dir, it) },
                prefix = "$dir/"
            )
        ) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val manifestFiles = selected.map { name ->
            val source = current.getValue(name)
            val copied = copiedHashes[name]
                ?: return CotaResult.Error("备份缺少文件：$name")
            if (copied != source.sha256) {
                return CotaResult.Error("文件校验失败：$name，请重试。")
            }
            CotaBackupFile(
                name = name,
                sha256 = source.sha256,
                sizeBytes = source.sizeBytes,
                mode = source.mode,
                uid = source.uid,
                gid = source.gid
            )
        }

        val manifest = CotaParsers.renderManifest(manifestFiles)
        val encoded = Base64.getEncoder()
            .encodeToString(manifest.toByteArray(Charsets.UTF_8))
        val wrote = b.exec(CotaCommandBuilder.writeManifest(dir, encoded))
        if (!wrote.ok) {
            return CotaResult.Error(
                "备份信息写入失败，请重试；若仍失败，请检查存储空间。"
            )
        }
        val readBack = when (val result = readManifestChecked(b, dir)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val parsed = CotaParsers.parseManifest(readBack)
            ?: return CotaResult.Error("备份清单格式无效")
        if (parsed.sortedBy { it.name } != manifestFiles.sortedBy { it.name }) {
            return CotaResult.Error("备份信息校验失败，请重新备份。")
        }

        ActionLog.info("COTA 备份完成：$dir（${manifestFiles.size} 个文件）")
        return CotaResult.Ok(CotaBackup(backupId, dir, manifestFiles))
    }

    suspend fun listBackups(): CotaResult<List<CotaBackupSummary>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val result = b.exec(CotaCommandBuilder.listBackups(backupRoot))
        if (!result.ok) {
            return CotaResult.Error(
                "读取备份列表失败，请刷新重试。"
            )
        }
        val ids = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .filter { CotaPaths.isSafeBackupId(it) }
            .distinct()
            .sortedDescending()
            .toList()
        val valid = ids.mapNotNull { id ->
            val summary = CotaBackupSummary(
                id,
                CotaPaths.backupDir(backupRoot, id)
            )
            when (loadBackup(summary)) {
                is CotaResult.Ok -> summary
                else -> {
                    ActionLog.warn("COTA：忽略没有有效清单的备份目录 $id")
                    null
                }
            }
        }
        return CotaResult.Ok(valid)
    }

    /**
     * Deletes one backup directory after proving it still has a valid
     * manifest. This is the only COTA operation that removes a directory;
     * everything else uses explicit `rm -f` on validated file paths.
     */
    suspend fun deleteBackup(backupId: String): CotaResult<Unit> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份编号无效：$backupId")
        }
        val summary = CotaBackupSummary(
            backupId,
            CotaPaths.backupDir(backupRoot, backupId)
        )
        when (val loaded = loadBackup(summary)) {
            is CotaResult.Ok -> Unit
            is CotaResult.Error -> return loaded
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val result = b.exec(CotaCommandBuilder.deleteBackup(backupRoot, backupId))
        if (!result.ok) {
            return CotaResult.Error(
                "删除备份失败，请刷新列表后重试。"
            )
        }
        val check = b.exec(CotaCommandBuilder.dirExists(summary.dir))
        if (!check.ok || check.output.trim() != "no") {
            return CotaResult.Error("删除后备份目录仍存在：${summary.dir}")
        }
        ActionLog.info("COTA 备份已删除：$backupId")
        return CotaResult.Ok(Unit)
    }

    suspend fun loadBackup(summary: CotaBackupSummary): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(summary.id)) {
            return CotaResult.Error("备份编号无效：${summary.id}")
        }
        val dir = CotaPaths.backupDir(backupRoot, summary.id)
        val manifest = when (val result = readManifestChecked(b, dir)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val files = CotaParsers.parseManifest(manifest)
            ?: return CotaResult.Error("备份清单格式无效：${summary.id}")
        if (files.isEmpty()) return CotaResult.Error("备份为空：${summary.id}")
        return CotaResult.Ok(CotaBackup(summary.id, dir, files))
    }

    suspend fun delete(
        names: Set<String>,
        backupId: String,
        riskAcknowledged: Boolean
    ): CotaResult<Unit> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!riskAcknowledged) return CotaResult.Error("未确认风险，已取消删除")
        if (names.isEmpty()) return CotaResult.Error("未选择文件")
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份编号无效")
        }
        if (names.any { !CotaPaths.isSafeRelativePath(it) }) {
            return CotaResult.Error("包含无法安全处理的文件名")
        }

        val summary = CotaBackupSummary(
            backupId,
            CotaPaths.backupDir(backupRoot, backupId)
        )
        val backup = when (val result = loadBackup(summary)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error ->
                return CotaResult.Error("没有可用备份，已取消删除：${result.message}")
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val backedUp = backup.files.associateBy { it.name }
        val notBackedUp = names.filter { it !in backedUp }
        if (notBackedUp.isNotEmpty()) {
            return CotaResult.Error("以下文件不在备份中：${notBackedUp.joinToString()}")
        }

        // A delete is only safe if the backup copies themselves are intact.
        val selectedBackupFiles = names.sorted().map { backedUp.getValue(it) }
        val backupHashes = when (
            val checked = hashChunks(
                b,
                selectedBackupFiles.map {
                    CotaPaths.backupPath(backup.dir, it.name)
                },
                prefix = "${backup.dir}/"
            )
        ) {
            is CotaResult.Ok -> checked.value
            is CotaResult.Error -> return checked
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (file in selectedBackupFiles) {
            val actual = backupHashes[file.name]
                ?: return CotaResult.Error("备份缺少文件：${file.name}")
            if (actual != file.sha256) {
                return CotaResult.Error("备份文件损坏，已取消删除：${file.name}")
            }
        }

        val current = when (val result = listFiles()) {
            is CotaResult.Ok -> result.value.associateBy { it.name }
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (name in names) {
            val now = current[name] ?: return CotaResult.Error("文件已不存在：$name")
            if (now.sha256 != backedUp.getValue(name).sha256) {
                return CotaResult.Error(
                    "备份后文件已变化，已取消删除：$name（请重新备份）"
                )
            }
        }

        val command = CotaCommandBuilder.deleteVerified(
            backup.dir,
            selectedBackupFiles
        )
        val result = b.exec(command)
        if (!result.ok) {
            return CotaResult.Error(
                "删除失败，请刷新列表确认当前状态后重试。"
            )
        }
        ActionLog.warn("COTA 已删除：${names.sorted().joinToString()}（备份 $backupId）")

        val remaining = when (val after = listFiles()) {
            is CotaResult.Ok -> after.value.map { it.name }.toSet()
            is CotaResult.Error ->
                return CotaResult.Error("删除已执行，但状态校验失败。请刷新列表确认。")
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val stillThere = names.filter { it in remaining }
        if (stillThere.isNotEmpty()) {
            return CotaResult.Error("删除后文件仍存在：${stillThere.joinToString()}")
        }
        return CotaResult.Ok(Unit)
    }

    suspend fun restore(
        backupId: String,
        names: Set<String> = emptySet()
    ): CotaResult<Unit> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份编号无效")
        }
        val summary = CotaBackupSummary(
            backupId,
            CotaPaths.backupDir(backupRoot, backupId)
        )
        val backup = when (val result = loadBackup(summary)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val backedUp = backup.files.associateBy { it.name }
        val selected =
            if (names.isEmpty()) backup.files.map { it.name }.toSet() else names
        val missing = selected.filter { it !in backedUp }
        if (missing.isNotEmpty()) {
            return CotaResult.Error("备份中没有：${missing.joinToString()}")
        }

        val dirCheck = b.exec(CotaCommandBuilder.dirExists(CotaPaths.SOURCE_DIR))
        if (!dirCheck.ok || dirCheck.output.trim() != "yes") {
            return CotaResult.Error(
                "目标目录不存在，已取消恢复：${CotaPaths.SOURCE_DIR}"
            )
        }

        val files = selected.sorted().map { backedUp.getValue(it) }
        val unsafeModes = files.filter {
            !CotaPaths.isSafeRestoreMode(it.mode)
        }
        if (unsafeModes.isNotEmpty()) {
            return CotaResult.Error(
                "备份包含无法安全恢复的特殊权限位，已取消：" +
                    unsafeModes.joinToString { "${it.name}(${it.mode})" }
            )
        }

        // Never overwrite a good current file with a corrupted backup: verify
        // the backup copies against the manifest before touching the target.
        val backupHashes = when (
            val checked = hashChunks(
                b,
                files.map { CotaPaths.backupPath(backup.dir, it.name) },
                prefix = "${backup.dir}/"
            )
        ) {
            is CotaResult.Ok -> checked.value
            is CotaResult.Error -> return checked
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (file in files) {
            val actual = backupHashes[file.name]
                ?: return CotaResult.Error("备份缺少文件：${file.name}")
            if (actual != file.sha256) {
                return CotaResult.Error("备份文件损坏，已取消恢复：${file.name}")
            }
        }

        val result = b.exec(CotaCommandBuilder.restore(backup.dir, files))
        if (!result.ok) {
            return CotaResult.Error(
                "恢复失败，请刷新列表确认当前状态后重试。"
            )
        }

        val hashes = when (
            val checked = hashChunks(
                b,
                files.map { CotaPaths.sourcePath(it.name) }
            )
        ) {
            is CotaResult.Ok -> checked.value
            is CotaResult.Error -> return checked
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (file in files) {
            val actual = hashes[file.name]
                ?: return CotaResult.Error("恢复后缺少文件：${file.name}")
            if (actual != file.sha256) {
                return CotaResult.Error("恢复已执行，但状态校验失败。请刷新列表确认。")
            }
        }

        ActionLog.info("COTA 恢复完成：${files.joinToString { it.name }}（备份 $backupId）")
        return CotaResult.Ok(Unit)
    }

    private suspend fun statChunks(
        backend: RootPrivilegeBackend,
        paths: List<String>
    ): CotaResult<Map<String, CotaParsers.CotaStat>> {
        if (paths.isEmpty()) return CotaResult.Ok(emptyMap())
        val stats = linkedMapOf<String, CotaParsers.CotaStat>()
        for (chunk in paths.chunked(COMMAND_CHUNK)) {
            val result = backend.exec(CotaCommandBuilder.stat(chunk))
            if (!result.ok) {
                return CotaResult.Error(
                    "读取文件信息失败，请刷新重试。"
                )
            }
            CotaParsers.parseStat(result.output).forEach { stats[it.name] = it }
        }
        return CotaResult.Ok(stats)
    }

    private suspend fun hashChunks(
        backend: RootPrivilegeBackend,
        paths: List<String>,
        prefix: String = CotaPaths.SOURCE_DIR + "/"
    ): CotaResult<Map<String, String>> {
        if (paths.isEmpty()) return CotaResult.Ok(emptyMap())
        val hashes = linkedMapOf<String, String>()
        for (chunk in paths.chunked(COMMAND_CHUNK)) {
            val result = backend.exec(CotaCommandBuilder.sha256(chunk))
            if (!result.ok) {
                return CotaResult.Error(
                    "文件校验失败，请刷新列表重试。"
                )
            }
            hashes.putAll(CotaParsers.parseSha256(result.output, prefix))
        }
        return CotaResult.Ok(hashes)
    }

    private suspend fun readManifestChecked(
        backend: RootPrivilegeBackend,
        backupDir: String
    ): CotaResult<String> {
        val sizeResult = backend.exec(CotaCommandBuilder.manifestSize(backupDir))
        if (!sizeResult.ok) {
            return CotaResult.Error(
                "读取备份信息失败，请刷新重试。"
            )
        }
        val size = sizeResult.output.trim().toLongOrNull()
        if (size == null || size < 0) {
            return CotaResult.Error("备份清单大小无效")
        }
        if (size > MAX_MANIFEST_BYTES) {
            return CotaResult.Error("备份清单过大，已拒绝")
        }
        val read = backend.exec(CotaCommandBuilder.readManifest(backupDir))
        if (!read.ok) {
            return CotaResult.Error("读取备份信息失败，请刷新重试。")
        }
        return CotaResult.Ok(read.output)
    }

    companion object {
        private const val COMMAND_CHUNK = 8
        private const val MAX_MANIFEST_BYTES = 64L * 1024L
        private val BACKUP_ID_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}
