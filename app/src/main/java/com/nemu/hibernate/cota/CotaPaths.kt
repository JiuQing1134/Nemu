package com.nemu.hibernate.cota

object CotaPaths {
    const val SOURCE_DIR = "/data/cota/para"
    const val DEFAULT_BACKUP_ROOT = "/storage/emulated/0/Nemu/cota-backup"
    const val MANIFEST_NAME = "manifest.tsv"
    const val TEMP_MANIFEST_NAME = ".manifest.tmp"
    const val DIR_MISSING = "__NEMU_COTA_DIR_MISSING__"

    private const val MAX_RELATIVE_DEPTH = 4
    private const val MAX_RELATIVE_LENGTH = 255
    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val BACKUP_ID = Regex("^\\d{8}-\\d{6}$")
    private val MODE = Regex("^[0-7]{1,4}$")

    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name)

    /**
     * COTA parameters live either at the root of /data/cota/para or inside a
     * vendor subsystem directory (for example thermal_config/...). Each
     * segment must independently satisfy [isSafeName]; empty, absolute and
     * traversal paths are rejected.
     */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_RELATIVE_LENGTH) return false
        val segments = path.split('/')
        return segments.size in 1..MAX_RELATIVE_DEPTH && segments.all(::isSafeName)
    }

    fun isSafeBackupId(id: String): Boolean = BACKUP_ID.matches(id)

    fun isSafeMode(mode: String): Boolean = MODE.matches(mode)

    /** Restore additionally refuses setuid/setgid/sticky bits. */
    fun isSafeRestoreMode(mode: String): Boolean =
        MODE.matches(mode) && (mode.length < 4 || mode[0] == '0')

    fun isReservedRootName(relativePath: String): Boolean =
        relativePath == MANIFEST_NAME || relativePath == TEMP_MANIFEST_NAME

    fun sourcePath(relativePath: String): String {
        require(isSafeRelativePath(relativePath)) {
            "unsafe COTA relative path: $relativePath"
        }
        return "$SOURCE_DIR/$relativePath"
    }

    /** Existing directory components of a validated COTA relative path. */
    fun sourceParentPaths(relativePath: String): List<String> {
        require(isSafeRelativePath(relativePath)) {
            "unsafe COTA relative path: $relativePath"
        }
        val segments = relativePath.split('/')
        return (1 until segments.size).map { depth ->
            SOURCE_DIR + "/" + segments.take(depth).joinToString("/")
        }
    }

    fun backupDir(root: String, id: String): String {
        require(isSafeBackupId(id)) { "unsafe backup id: $id" }
        return "$root/$id"
    }

    fun backupPath(dir: String, relativePath: String): String {
        require(isSafeRelativePath(relativePath)) {
            "unsafe COTA relative path: $relativePath"
        }
        return "$dir/$relativePath"
    }
}
