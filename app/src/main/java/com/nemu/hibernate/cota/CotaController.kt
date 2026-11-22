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
    private val backupRoot: String = CotaPaths.DEFAULT_BACKUP_ROOT,
    private val clock: () -> String = { LocalDateTime.now().format(BACKUP_ID_FORMAT) }
) {
    private fun activeBackend(): RootPrivilegeBackend? =
        if (enabled) backend else null

    suspend fun listFiles(): CotaResult<List<CotaFile>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val listing = b.exec(CotaCommandBuilder.listFiles())
        if (!listing.ok) {
            return CotaResult.Error(
                "读取目录失败（${listing.exitCode}）：${listing.output.take(300)}"
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
                    kind = CotaKnownFiles.kindOf(name)
                )
            }
        )
    }

    suspend fun backup(names: Set<String>): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (names.isEmpty()) return CotaResult.Error("未选择文件")
        if (names.any { !CotaPaths.isSafeName(it) }) {
            return CotaResult.Error("包含无法安全处理的文件名")
        }
        val selected = names.sorted()

        val current = when (val result = listFiles()) {
            is CotaResult.Ok -> result.value.associateBy { it.name }
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val missing = selected.filter { it !in current }
        if (missing.isNotEmpty()) {
            return CotaResult.Error("文件不存在或已变化：${missing.joinToString()}")
        }

        val backupId = clock()
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份时间戳无效：$backupId")
        }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        val sources = selected.map { CotaPaths.sourcePath(it) }
        val copy = b.exec(CotaCommandBuilder.backup(backupRoot, backupId, sources))
        if (!copy.ok) {
            return CotaResult.Error(
                "备份复制失败（${copy.exitCode}）：${copy.output.take(300)}"
            )
        }

        val copiedHashes = when (
            val result = hashChunks(
                b,
                selected.map { CotaPaths.backupPath(dir, it) }
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
                return CotaResult.Error("备份校验失败：$name")
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
                "写入备份清单失败（${wrote.exitCode}）：${wrote.output.take(300)}"
            )
        }
        val readBack = b.exec(CotaCommandBuilder.readManifest(dir))
        if (!readBack.ok) {
            return CotaResult.Error("备份清单回读失败（${readBack.exitCode}）")
        }
        val parsed = CotaParsers.parseManifest(readBack.output)
            ?: return CotaResult.Error("备份清单格式无效")
        if (parsed.sortedBy { it.name } != manifestFiles.sortedBy { it.name }) {
            return CotaResult.Error("备份清单校验失败")
        }

        ActionLog.info("COTA 备份完成：$dir（${manifestFiles.size} 个文件）")
        return CotaResult.Ok(CotaBackup(backupId, dir, manifestFiles))
    }

    suspend fun listBackups(): CotaResult<List<CotaBackupSummary>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val result = b.exec(CotaCommandBuilder.listBackups(backupRoot))
        if (!result.ok) {
            return CotaResult.Error(
                "读取备份目录失败（${result.exitCode}）：${result.output.take(300)}"
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
        return CotaResult.Ok(
            ids.map { CotaBackupSummary(it, CotaPaths.backupDir(backupRoot, it)) }
        )
    }

    suspend fun loadBackup(summary: CotaBackupSummary): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(summary.id)) {
            return CotaResult.Error("备份编号无效：${summary.id}")
        }
        val dir = CotaPaths.backupDir(backupRoot, summary.id)
        val result = b.exec(CotaCommandBuilder.readManifest(dir))
        if (!result.ok) {
            return CotaResult.Error("读取备份清单失败（${result.exitCode}）")
        }
        val files = CotaParsers.parseManifest(result.output)
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
        if (names.any { !CotaPaths.isSafeName(it) }) {
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
                }
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

        val command = CotaCommandBuilder.delete(
            names.sorted().map { CotaPaths.sourcePath(it) }
        )
        val result = b.exec(command)
        if (!result.ok) {
            return CotaResult.Error(
                "删除失败（${result.exitCode}）：${result.output.take(300)}"
            )
        }
        ActionLog.warn("COTA 已删除：${names.sorted().joinToString()}（备份 $backupId）")

        val remaining = when (val after = listFiles()) {
            is CotaResult.Ok -> after.value.map { it.name }.toSet()
            is CotaResult.Error ->
                return CotaResult.Error("删除命令已执行，但校验失败：${after.message}")
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

        // Never overwrite a good current file with a corrupted backup: verify
        // the backup copies against the manifest before touching the target.
        val backupHashes = when (
            val checked = hashChunks(
                b,
                files.map { CotaPaths.backupPath(backup.dir, it.name) }
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
                "恢复失败（${result.exitCode}）：${result.output.take(300)}"
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
                return CotaResult.Error("恢复校验失败：${file.name}")
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
                    "读取文件属性失败（${result.exitCode}）：${result.output.take(300)}"
                )
            }
            CotaParsers.parseStat(result.output).forEach { stats[it.name] = it }
        }
        return CotaResult.Ok(stats)
    }

    private suspend fun hashChunks(
        backend: RootPrivilegeBackend,
        paths: List<String>
    ): CotaResult<Map<String, String>> {
        if (paths.isEmpty()) return CotaResult.Ok(emptyMap())
        val hashes = linkedMapOf<String, String>()
        for (chunk in paths.chunked(COMMAND_CHUNK)) {
            val result = backend.exec(CotaCommandBuilder.sha256(chunk))
            if (!result.ok) {
                return CotaResult.Error(
                    "SHA-256 失败（${result.exitCode}）：${result.output.take(300)}"
                )
            }
            hashes.putAll(CotaParsers.parseSha256(result.output))
        }
        return CotaResult.Ok(hashes)
    }

    companion object {
        private const val COMMAND_CHUNK = 8
        private val BACKUP_ID_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}
