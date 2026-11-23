package com.nemu.hibernate.cota

import com.nemu.hibernate.core.ShellQuote

object CotaCommandBuilder {
    fun listFiles(): String =
        "if [ -d ${q(CotaPaths.SOURCE_DIR)} ]; then " +
            "find ${q(CotaPaths.SOURCE_DIR)} -mindepth 1 -maxdepth 4 " +
            "-type f -print0; " +
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

    /**
     * Refuses a source path that resolves outside /data/cota/para or passes
     * through a symlink. `realpath -m` is available in toybox on this device.
     */
    internal fun sourceGuards(relativePath: String): String {
        val target = CotaPaths.sourcePath(relativePath)
        val checks = mutableListOf(
            "case \"$(realpath -m ${q(target)})\" in " +
                "${q(CotaPaths.SOURCE_DIR)}/*) ;; *) exit 1;; esac"
        )
        CotaPaths.sourceParentPaths(relativePath).forEach { parent ->
            checks += "[ ! -L ${q(parent)} ]"
        }
        checks += "[ ! -L ${q(target)} ]"
        return checks.joinToString(" && ")
    }

    fun backup(backupRoot: String, backupId: String, names: List<String>): String {
        require(names.isNotEmpty()) { "names must not be empty" }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        val parts = mutableListOf(
            "mkdir -p ${q(backupRoot)}",
            "mkdir ${q(dir)}"
        )
        names.forEach { name ->
            val source = CotaPaths.sourcePath(name)
            val target = CotaPaths.backupPath(dir, name)
            val parent = target.substringBeforeLast('/')
            if (parent != dir) parts += "mkdir -p ${q(parent)}"
            parts += sourceGuards(name)
            parts += "cp -p ${q(source)} ${q(target)}"
        }
        return parts.joinToString(" && ")
    }

    fun writeManifest(backupDir: String, manifestBase64: String): String {
        val temp = "$backupDir/${CotaPaths.TEMP_MANIFEST_NAME}"
        val final = "$backupDir/${CotaPaths.MANIFEST_NAME}"
        return "printf '%s' ${q(manifestBase64)} | base64 -d > ${q(temp)}" +
            " && sync && mv -f ${q(temp)} ${q(final)}"
    }

    fun readManifest(backupDir: String): String =
        "cat ${q("$backupDir/${CotaPaths.MANIFEST_NAME}")}"

    fun manifestSize(backupDir: String): String =
        "stat -c %s ${q("$backupDir/${CotaPaths.MANIFEST_NAME}")}"

    fun listBackups(root: String): String =
        "if [ -d ${q(root)} ]; then " +
            "find ${q(root)} -mindepth 1 -maxdepth 1 -type d -name '20*' -print; " +
            "fi"

    /**
     * Re-checks every target against its verified backup copy in the same
     * shell that removes it. A COTA sync changing a file between the
     * controller's hash check and `rm` therefore aborts the whole delete.
     */
    fun deleteVerified(
        backupDir: String,
        files: List<CotaBackupFile>
    ): String {
        require(files.isNotEmpty()) { "files must not be empty" }
        val checks = files.joinToString(" && ") { file ->
            val backup = CotaPaths.backupPath(backupDir, file.name)
            val target = CotaPaths.sourcePath(file.name)
            sourceGuards(file.name) +
                " && [ -f ${q(backup)} ] && [ ! -L ${q(backup)} ]" +
                " && cmp -s ${q(backup)} ${q(target)}"
        }
        val targets = files.joinToString(" ") {
            q(CotaPaths.sourcePath(it.name))
        }
        return "$checks && rm -f $targets"
    }

    fun restore(backupDir: String, files: List<CotaBackupFile>): String {
        require(files.isNotEmpty()) { "files must not be empty" }
        return files.joinToString(" && ") { file ->
            require(CotaPaths.isSafeRestoreMode(file.mode)) {
                "unsafe mode: ${file.mode}"
            }
            require(file.uid >= 0 && file.gid >= 0) { "unsafe uid/gid" }
            val source = CotaPaths.backupPath(backupDir, file.name)
            val target = CotaPaths.sourcePath(file.name)
            val parent = target.substringBeforeLast('/')
            sourceGuards(file.name) +
                " && [ -f ${q(source)} ] && [ ! -L ${q(source)} ]" +
                " && [ \"$(sha256sum ${q(source)} | cut -d' ' -f1)\" = " +
                q(file.sha256) + " ]" +
                " && mkdir -p ${q(parent)}" +
                " && [ ! -L ${q(parent)} ]" +
                " && [ ! -L ${q(target)} ]" +
                // Never cp into (then chown/chmod) a directory that took the
                // file's place; abort before touching its metadata.
                " && [ ! -d ${q(target)} ]" +
                " && cp -p ${q(source)} ${q(target)}" +
                " && chown ${q("${file.uid}:${file.gid}")} ${q(target)}" +
                " && chmod ${q(file.mode)} ${q(target)}"
        }
    }

    fun dirExists(path: String): String =
        "[ -d ${q(path)} ] && echo yes || echo no"

    private fun q(value: String): String = ShellQuote.quote(value)
}
