package com.nemu.hibernate.cota

import java.util.Locale

/**
 * One directory or file inside a [CotaFileGroup]. [path] is always relative to
 * /data/cota/para, so it can be passed back to selection/backup unchanged.
 * Directories carry every file below them in [filesUnder] for select-all and
 * size summaries; files carry their original [file].
 */
data class CotaTreeNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val file: CotaFile? = null,
    val children: List<CotaTreeNode> = emptyList(),
    val filesUnder: List<CotaFile> = emptyList()
) {
    val totalSizeBytes: Long = filesUnder.fold(0L) { total, item ->
        if (Long.MAX_VALUE - total < item.sizeBytes) Long.MAX_VALUE
        else total + item.sizeBytes
    }
}

/**
 * Builds a directory tree from the flat relative paths returned by the COTA
 * listing. The first path segment is the subsystem group, so the group key is
 * stripped before nesting; the root group has no prefix at all.
 *
 * The builder is deliberately tolerant: duplicate paths collapse, and a
 * corrupt listing where one path is also a directory prefix is treated as a
 * directory (the impossible file leaf is dropped rather than crashing the UI).
 */
object CotaTree {
    private val NODE_ORDER = compareBy<CotaTreeNode>(
        { !it.isDirectory },
        { it.name.lowercase(Locale.ROOT) },
        { it.name }
    )

    fun build(group: CotaFileGroup): List<CotaTreeNode> =
        build(group.key, group.files)

    fun build(groupKey: String, files: List<CotaFile>): List<CotaTreeNode> {
        val roots = linkedMapOf<String, MutableNode>()
        // find already de-duplicates, but a corrupt backup/manual list may not.
        files.associateBy { it.name }.values.forEach { file ->
            val relative = when {
                groupKey.isEmpty() -> file.name
                file.name.startsWith("$groupKey/") ->
                    file.name.removePrefix("$groupKey/")
                else -> return@forEach
            }
            val segments = relative.split('/')
            if (segments.isEmpty() ||
                segments.any { it.isEmpty() || it == "." || it == ".." }
            ) {
                return@forEach
            }
            var level: MutableMap<String, MutableNode> = roots
            var pathAcc = groupKey
            segments.forEachIndexed { index, segment ->
                pathAcc = if (pathAcc.isEmpty()) segment else "$pathAcc/$segment"
                val node = level.getOrPut(segment) {
                    MutableNode(path = pathAcc, name = segment)
                }
                if (index == segments.lastIndex) {
                    // A file cannot also be a parent directory. If the prefix
                    // already exists, the directory wins and the impossible
                    // file is ignored.
                    if (node.children.isEmpty()) node.file = file
                } else {
                    node.file = null
                    level = node.children
                }
            }
        }
        return roots.values
            .mapNotNull { it.toNode() }
            .sortedWith(NODE_ORDER)
    }

    private class MutableNode(
        val path: String,
        val name: String,
        var file: CotaFile? = null,
        val children: MutableMap<String, MutableNode> = linkedMapOf()
    ) {
        fun toNode(): CotaTreeNode? {
            val kids = children.values
                .mapNotNull { it.toNode() }
                .sortedWith(NODE_ORDER)
            val ownFile = file
            if (kids.isEmpty() && ownFile == null) return null
            val isDirectory = kids.isNotEmpty() || ownFile == null
            return CotaTreeNode(
                path = path,
                name = name,
                isDirectory = isDirectory,
                file = if (isDirectory) null else ownFile,
                children = kids,
                filesUnder = if (isDirectory) {
                    kids.flatMap { it.filesUnder }
                } else {
                    listOfNotNull(ownFile)
                }
            )
        }
    }
}
