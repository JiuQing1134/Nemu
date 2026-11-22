package com.nemu.hibernate.cota

import com.nemu.hibernate.core.ShellQuote

object CotaCommandBuilder {
    fun listFiles(): String =
        "if [ -d ${q(CotaPaths.SOURCE_DIR)} ]; then " +
            "find ${q(CotaPaths.SOURCE_DIR)} -maxdepth 1 -type f -print; " +
            "else echo ${q(CotaPaths.DIR_MISSING)}; fi"

    /** Read file metadata under COTA subsystem folders without reading contents. */
    fun listSubsystemFiles(): String =
        "if [ -d ${q(CotaPaths.SOURCE_DIR)} ]; then " +
            "find ${q(CotaPaths.SOURCE_DIR)} -mindepth 2 -maxdepth 4 -type f " +
            "-exec stat -c '%n|%s' {} \\; ; fi"

    fun stat(paths: List<String>): String {
        require(paths.isNotEmpty()) { "paths must not be empty" }
        return "stat -c '%n|%s|%a|%u|%g' " + paths.joinToString(" ") { q(it) }
    }

    fun sha256(paths: List<String>): String {
        require(paths.isNotEmpty()) { "paths must not be empty" }
        return "sha256sum " + paths.joinToString(" ") { q(it) }
    }

    fun backup(backupRoot: String, backupId: String, sources: List<String>): String {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        return "mkdir -p ${q(backupRoot)} && mkdir ${q(dir)} && cp -p " +
            sources.joinToString(" ") { q(it) } + " " + q("$dir/")
    }

    fun writeManifest(backupDir: String, manifestBase64: String): String =
        "printf '%s' ${q(manifestBase64)} | base64 -d > " +
            q("$backupDir/${CotaPaths.MANIFEST_NAME}")

    fun readManifest(backupDir: String): String =
        "cat ${q("$backupDir/${CotaPaths.MANIFEST_NAME}")}"

    fun listBackups(root: String): String =
        "if [ -d ${q(root)} ]; then " +
            "find ${q(root)} -mindepth 1 -maxdepth 1 -type d -name '20*' -print; " +
            "fi"

    fun delete(sources: List<String>): String {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        return "rm -f " + sources.joinToString(" ") { q(it) }
    }

    fun restore(backupDir: String, files: List<CotaBackupFile>): String {
        require(files.isNotEmpty()) { "files must not be empty" }
        return files.joinToString(" && ") { file ->
            require(CotaPaths.isSafeMode(file.mode)) { "unsafe mode: ${file.mode}" }
            require(file.uid >= 0 && file.gid >= 0) { "unsafe uid/gid" }
            val source = CotaPaths.backupPath(backupDir, file.name)
            val target = CotaPaths.sourcePath(file.name)
            "cp -p ${q(source)} ${q(target)}" +
                " && chown ${q("${file.uid}:${file.gid}")} ${q(target)}" +
                " && chmod ${q(file.mode)} ${q(target)}"
        }
    }

    fun dirExists(path: String): String =
        "[ -d ${q(path)} ] && echo yes || echo no"

    private fun q(value: String): String = ShellQuote.quote(value)
}
