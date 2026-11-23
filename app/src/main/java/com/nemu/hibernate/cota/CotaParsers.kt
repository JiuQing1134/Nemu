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
        // `find -print0` keeps a hostile newline inside one record; line mode
        // is kept for tests and old backups.
        val entries = if ('\u0000' in output) {
            output.split('\u0000')
        } else {
            output.lineSequence().toList()
        }
        entries.forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith(prefix)) {
                val name = line.removePrefix(prefix)
                if (CotaPaths.isSafeRelativePath(name)) names += name else skipped++
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
        val gid: Int,
        val modifiedAtSeconds: Long? = null
    )

    fun parseStat(output: String): List<CotaStat> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val parts = line.split('|')
            // Six fields with `%Y`; five fields keeps old/partial output
            // readable instead of dropping the whole file row.
            if (parts.size !in 5..6) return@mapNotNull null
            val prefix = CotaPaths.SOURCE_DIR + "/"
            val path = parts[0]
            if (!path.startsWith(prefix)) return@mapNotNull null
            val name = path.removePrefix(prefix)
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            val mode = parts[2]
            val uid = parts[3].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[4].toIntOrNull() ?: return@mapNotNull null
            if (!CotaPaths.isSafeRelativePath(name) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            // A missing or unparseable time must never hide the file itself.
            val modifiedAt = parts.getOrNull(5)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            CotaStat(name, size, mode, uid, gid, modifiedAt)
        }.toList()

    /**
     * Parse `sha256sum` output. [prefix] is the root the relative names are
     * measured from: the live source directory for listings, or a specific
     * backup directory when verifying copies.
     */
    fun parseSha256(
        output: String,
        prefix: String = CotaPaths.SOURCE_DIR + "/"
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.length <= 66) return@forEach
            val hash = line.substring(0, 64)
            if (!SHA256.matches(hash)) return@forEach
            val marker = line.substring(64, 66)
            if (marker != "  " && marker != " *") return@forEach
            val path = line.substring(66)
            if (!path.startsWith(prefix)) return@forEach
            val name = path.removePrefix(prefix)
            if (!CotaPaths.isSafeRelativePath(name)) return@forEach
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
            val size = parts[2].toLongOrNull()?.takeIf { it >= 0L }
                ?: return@mapNotNull null
            val mode = parts[3]
            val uid = parts[4].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[5].toIntOrNull() ?: return@mapNotNull null
            if (!CotaPaths.isSafeRelativePath(name) ||
                !SHA256.matches(sha) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            CotaBackupFile(name, sha, size, mode, uid, gid)
        }
        if (files.size != lines.size - 1) return null
        if (files.any { it.sizeBytes < 0L }) return null
        if (files.map { it.name }.toSet().size != files.size) return null
        return files
    }
}
