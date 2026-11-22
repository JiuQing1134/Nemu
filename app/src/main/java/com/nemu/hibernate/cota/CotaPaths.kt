package com.nemu.hibernate.cota

object CotaPaths {
    const val SOURCE_DIR = "/data/cota/para"
    const val DEFAULT_BACKUP_ROOT = "/storage/emulated/0/Nemu/cota-backup"
    const val MANIFEST_NAME = "manifest.tsv"
    const val DIR_MISSING = "__NEMU_COTA_DIR_MISSING__"

    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val BACKUP_ID = Regex("^\\d{8}-\\d{6}$")
    private val MODE = Regex("^[0-7]{3,4}$")

    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name)

    fun isSafeBackupId(id: String): Boolean = BACKUP_ID.matches(id)

    fun isSafeMode(mode: String): Boolean = MODE.matches(mode)

    fun sourcePath(name: String): String {
        require(isSafeName(name)) { "unsafe COTA file name: $name" }
        return "$SOURCE_DIR/$name"
    }

    fun backupDir(root: String, id: String): String {
        require(isSafeBackupId(id)) { "unsafe backup id: $id" }
        return "$root/$id"
    }

    fun backupPath(dir: String, name: String): String {
        require(isSafeName(name)) { "unsafe COTA file name: $name" }
        return "$dir/$name"
    }
}
