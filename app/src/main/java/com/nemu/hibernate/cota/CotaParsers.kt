package com.nemu.hibernate.cota

object CotaParsers {
    private const val MANIFEST_HEADER = "#nemu-cota-backup-v1"
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private const val MAX_SAMPLE_PATHS = 3

    fun isDirMissing(output: String): Boolean =
        output.trim() == CotaPaths.DIR_MISSING

    fun parseFileNames(output: String): Pair<List<String>, Int> {
        var skipped = 0
        val names = linkedSetOf<String>()
        val prefix = CotaPaths.SOURCE_DIR + "/"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith(prefix)) {
                val name = line.removePrefix(prefix)
                if (CotaPaths.isSafeName(name)) names += name else skipped++
            } else {
                skipped++
            }
        }
        return names.sorted() to skipped
    }

    /** Parse only paths and sizes emitted by the read-only subsystem inventory. */
    fun parseSubsystemFiles(output: String): List<CotaDirectoryInventory> {
        data class MutableInventory(
            val files: MutableSet<String> = linkedSetOf(),
            var totalSize: Long = 0L
        )

        val groups = linkedMapOf<String, MutableInventory>()
        val prefix = CotaPaths.SOURCE_DIR + "/"
        output.lineSequence().forEach { raw ->
            val parts = raw.trimEnd('\r').split('|', limit = 2)
            if (parts.size != 2) return@forEach
            val fullPath = parts[0]
            val size = parts[1].toLongOrNull()?.takeIf { it >= 0L }
                ?: return@forEach
            if (!fullPath.startsWith(prefix)) return@forEach

            val relativePath = fullPath.removePrefix(prefix)
            val segments = relativePath.split('/')
            if (segments.size !in 2..4 || segments.any { !CotaPaths.isSafeName(it) }) {
                return@forEach
            }

            val directory = segments.first()
            val inventory = groups.getOrPut(directory) { MutableInventory() }
            if (inventory.files.add(relativePath)) {
                inventory.totalSize =
                    if (Long.MAX_VALUE - inventory.totalSize < size) Long.MAX_VALUE
                    else inventory.totalSize + size
            }
        }

        return groups.toSortedMap().map { (name, inventory) ->
            CotaDirectoryInventory(
                name = name,
                fileCount = inventory.files.size,
                totalSizeBytes = inventory.totalSize,
                samplePaths = inventory.files.sorted().take(MAX_SAMPLE_PATHS)
            )
        }
    }

    data class CotaStat(
        val name: String,
        val sizeBytes: Long,
        val mode: String,
        val uid: Int,
        val gid: Int
    )

    fun parseStat(output: String): List<CotaStat> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val path = parts[0]
            val name = path.substringAfterLast('/')
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            val mode = parts[2]
            val uid = parts[3].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[4].toIntOrNull() ?: return@mapNotNull null
            if (!path.startsWith(CotaPaths.SOURCE_DIR + "/") ||
                !CotaPaths.isSafeName(name) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            CotaStat(name, size, mode, uid, gid)
        }.toList()

    fun parseSha256(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.length <= 66) return@forEach
            val hash = line.substring(0, 64)
            if (!SHA256.matches(hash)) return@forEach
            val marker = line.substring(64, 66)
            if (marker != "  " && marker != " *") return@forEach
            val path = line.substring(66)
            val name = path.substringAfterLast('/')
            if (!CotaPaths.isSafeName(name)) return@forEach
            result[name] = hash
        }
        return result
    }

    fun renderManifest(files: List<CotaBackupFile>): String = buildString {
        append(MANIFEST_HEADER).append('\n')
        files.sortedBy { it.name }.forEach { file ->
            append(file.name).append('\t')
            append(file.sha256).append('\t')
            append(file.sizeBytes).append('\t')
            append(file.mode).append('\t')
            append(file.uid).append('\t')
            append(file.gid).append('\n')
        }
    }

    fun parseManifest(text: String): List<CotaBackupFile>? {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.firstOrNull() != MANIFEST_HEADER) return null
        val files = lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 6) return@mapNotNull null
            val name = parts[0]
            val sha = parts[1]
            val size = parts[2].toLongOrNull() ?: return@mapNotNull null
            val mode = parts[3]
            val uid = parts[4].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[5].toIntOrNull() ?: return@mapNotNull null
            if (!CotaPaths.isSafeName(name) ||
                !SHA256.matches(sha) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            CotaBackupFile(name, sha, size, mode, uid, gid)
        }
        return files.takeIf { it.size == lines.size - 1 }
    }
}
