package com.nemu.hibernate.policy

import com.nemu.hibernate.core.DeepBackupInfo

/**
 * TSV manifest for Canta-style per-user deep uninstall backups.
 *
 * One line per package, fields in order:
 * packageName, label, versionCode, versionName, backupDir, timestampMillis,
 * files (comma separated), systemApp (0/1).
 *
 * The manifest is plain text because it lives under `/data/adb`, is written
 * through a root shell and must survive an app reinstall. [decode] never
 * throws: malformed lines are skipped.
 */
object DeepUninstallManifest {
    const val ROOT = "/data/adb/nemu/deep_uninstall"
    const val APK_ROOT = "$ROOT/apk"
    const val MANIFEST_PATH = "$ROOT/manifest.tsv"

    /** Module v1.20 writes this capability marker after it is installed. */
    const val READY_PATH = "$ROOT/auto_restore_ready"
    const val READY_VERSION = "1.20"

    /** Active KernelSU module directory; `disable`/`remove` mean inactive. */
    const val MODULE_DIR = "/data/adb/modules/nemu_boot_autostart"

    /**
     * A second, user-visible copy for transfer. The /data/adb copy remains the
     * restore source; this one is what a file manager / PC can pick up.
     */
    const val DOWNLOAD_ROOT = "/storage/emulated/0/Download/Nemu/deep_uninstall"
    const val DOWNLOAD_APK_ROOT = "$DOWNLOAD_ROOT/apk"
    const val DOWNLOAD_MANIFEST_PATH = "$DOWNLOAD_ROOT/manifest.tsv"

    /** `/data/adb/.../apk/<pkg>/<v>` -> `Download/Nemu/.../apk/<pkg>/<v>`. */
    fun downloadDir(primaryDir: String): String =
        if (primaryDir.startsWith("$APK_ROOT/")) {
            DOWNLOAD_APK_ROOT + primaryDir.removePrefix(APK_ROOT)
        } else {
            primaryDir
        }

    private const val FIELD_COUNT = 8
    private const val NULL_FIELD = "-"
    private const val SYSTEM_FLAG = "1"
    private const val USER_FLAG = "0"

    fun encode(info: DeepBackupInfo): String {
        val files = info.files
            .map { sanitizeFileName(it) }
            .filter { it.isNotEmpty() }
        val versionName = info.versionName
            ?.let { sanitizeText(it) }
            ?.takeIf { it.isNotBlank() }
            ?: NULL_FIELD
        return listOf(
            sanitizeText(info.packageName).trim(),
            sanitizeText(info.label),
            info.versionCode.toString(),
            versionName,
            sanitizeText(info.dir).trim(),
            info.timestampMillis.toString(),
            files.joinToString(","),
            if (info.systemApp) SYSTEM_FLAG else USER_FLAG
        ).joinToString("\t")
    }

    fun decode(line: String): DeepBackupInfo? {
        val raw = line.removeSuffix("\r")
        if (raw.isBlank()) return null
        val fields = raw.split('\t')
        if (fields.size != FIELD_COUNT) return null

        val packageName = fields[0].trim()
        if (packageName.isEmpty()) return null
        val backupDir = fields[4].trim()
        if (backupDir.isEmpty()) return null
        val versionCode = fields[2].toLongOrNull() ?: return null
        val timestampMillis = fields[5].toLongOrNull() ?: return null
        val systemApp = when (fields[7].trim()) {
            SYSTEM_FLAG -> true
            USER_FLAG -> false
            else -> return null
        }
        val files = fields[6].split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (files.isEmpty()) return null
        if (files.any { !isValidFileName(it) }) return null

        val versionName = fields[3]
            .takeIf { it.isNotBlank() && it != NULL_FIELD }

        return DeepBackupInfo(
            packageName = packageName,
            label = fields[1],
            versionCode = versionCode,
            versionName = versionName,
            dir = backupDir,
            files = files,
            timestampMillis = timestampMillis,
            systemApp = systemApp
        )
    }

    fun decodeAll(text: String): List<DeepBackupInfo> =
        text.lineSequence().mapNotNull { decode(it) }.toList()

    fun encodeAll(entries: List<DeepBackupInfo>): String =
        if (entries.isEmpty()) {
            ""
        } else {
            entries.joinToString(separator = "\n", postfix = "\n") { encode(it) }
        }

    /**
     * Decodes [lines], replaces the entry for [info]'s package (or appends it)
     * and keeps every other valid entry in its original order.
     */
    fun replace(lines: List<String>, info: DeepBackupInfo): List<DeepBackupInfo> {
        val existing = decodeAll(lines.joinToString("\n"))
        val result = ArrayList<DeepBackupInfo>(existing.size + 1)
        var replaced = false
        for (entry in existing) {
            if (entry.packageName == info.packageName) {
                if (!replaced) {
                    result += info
                    replaced = true
                }
            } else {
                result += entry
            }
        }
        if (!replaced) result += info
        return result
    }

    /** TSV fields are line-based, so tabs/newlines/pipes become spaces. */
    private fun sanitizeText(value: String): String =
        value
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', ' ')

    private fun sanitizeFileName(value: String): String =
        sanitizeText(value).replace(',', '_').trim()

    private fun isValidFileName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\')
}
