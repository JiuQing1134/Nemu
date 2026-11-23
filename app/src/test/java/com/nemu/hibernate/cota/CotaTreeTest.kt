package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaTreeTest {
    private fun file(name: String, size: Long = 10L) = CotaFile(
        name = name,
        sizeBytes = size,
        sha256 = "a".repeat(64),
        mode = "644",
        uid = 0,
        gid = 0,
        kind = CotaKnownFiles.kindOf(name)
    )

    private fun List<CotaTreeNode>.names(): List<String> = map { it.name }

    private fun List<CotaTreeNode>.child(name: String): CotaTreeNode =
        first { it.name == name }

    private fun CotaTreeNode.child(name: String): CotaTreeNode =
        children.first { it.name == name }

    @Test
    fun directoriesComeBeforeFilesAndPathsStayRelativeToTheRoot() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/version.txt", 3),
                file("thermal_config/zeta/two.xml", 2),
                file("thermal_config/alpha/one.xml", 1),
                file("thermal_config/alpha/deep/three.xml", 4)
            )
        )

        assertEquals(listOf("alpha", "zeta", "version.txt"), nodes.names())
        val alpha = nodes.child("alpha")
        assertTrue(alpha.isDirectory)
        assertEquals("thermal_config/alpha", alpha.path)
        assertEquals(listOf("deep", "one.xml"), alpha.children.names())
        val deep = alpha.child("deep")
        assertTrue(deep.isDirectory)
        assertEquals("thermal_config/alpha/deep", deep.path)
        assertEquals(listOf("three.xml"), deep.children.names())
        val version = nodes.child("version.txt")
        assertFalse(version.isDirectory)
        assertEquals("thermal_config/version.txt", version.path)
    }

    @Test
    fun directoriesAggregateEveryFileInTheirSubtree() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha/one.xml", 1),
                file("thermal_config/alpha/deep/three.xml", 4),
                file("thermal_config/beta/two.xml", 2)
            )
        )

        val alpha = nodes.child("alpha")
        assertEquals(
            listOf(
                "thermal_config/alpha/deep/three.xml",
                "thermal_config/alpha/one.xml"
            ),
            alpha.filesUnder.map { it.name }.sorted()
        )
        assertEquals(5L, alpha.totalSizeBytes)
        assertEquals(2L, nodes.child("beta").totalSizeBytes)
    }

    @Test
    fun rootGroupKeepsTopLevelFilesSelectableWithoutSyntheticDirectories() {
        val nodes = CotaTree.build(
            groupKey = "",
            files = listOf(file("root-b.txt", 2), file("root-a.txt", 1))
        )

        assertEquals(listOf("root-a.txt", "root-b.txt"), nodes.names())
        assertTrue(nodes.all { !it.isDirectory })
        assertEquals(
            listOf("root-a.txt", "root-b.txt"),
            nodes.mapNotNull { it.file?.name }
        )
    }

    @Test
    fun caseInsensitiveNameOrderIsStableForDirectoriesAndFiles() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/Beta/x.xml"),
                file("thermal_config/alpha/x.xml"),
                file("thermal_config/Zeta.txt"),
                file("thermal_config/Alpha.txt")
            )
        )

        assertEquals(listOf("alpha", "Beta", "Alpha.txt", "Zeta.txt"), nodes.names())
    }

    @Test
    fun duplicatePathsDoNotDuplicateNodesOrSize() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha/one.xml", 1),
                file("thermal_config/alpha/one.xml", 1)
            )
        )

        val alpha = nodes.child("alpha")
        assertEquals(1, alpha.children.size)
        assertEquals(1L, alpha.totalSizeBytes)
        assertEquals(listOf("one.xml"), alpha.children.names())
    }

    @Test
    fun deepPathsKeepEveryAncestorSeparate() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(file("thermal_config/a/b/c/deep.xml", 9))
        )

        val a = nodes.child("a")
        val b = a.child("b")
        val c = b.child("c")
        assertEquals("thermal_config/a", a.path)
        assertEquals("thermal_config/a/b", b.path)
        assertEquals("thermal_config/a/b/c", c.path)
        assertEquals(listOf("deep.xml"), c.children.names())
        assertEquals(9L, a.totalSizeBytes)
        assertEquals(9L, b.totalSizeBytes)
        assertEquals(9L, c.totalSizeBytes)
    }

    @Test
    fun groupConvenienceBuildStripsTheSubsystemPrefix() {
        val group = CotaFileGroups.group(
            listOf(file("thermal_config/alpha/one.xml", 1))
        ).single()

        val nodes = CotaTree.build(group)

        assertEquals(listOf("alpha"), nodes.names())
        assertEquals("thermal_config/alpha", nodes.child("alpha").path)
    }

    @Test
    fun sizeAggregationClampsInsteadOfOverflowing() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha/huge.bin", Long.MAX_VALUE),
                file("thermal_config/alpha/more.bin", 1)
            )
        )

        assertEquals(Long.MAX_VALUE, nodes.child("alpha").totalSizeBytes)
    }

    @Test
    fun everyNodePathCanBeCopiedAsASafeAbsolutePath() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/version.txt", 1),
                file("thermal_config/alpha/deep/three.xml", 2)
            )
        )
        fun flatten(items: List<CotaTreeNode>): List<CotaTreeNode> =
            items + items.flatMap { flatten(it.children) }

        val all = flatten(nodes)
        all.forEach { node ->
            assertTrue(
                "bad copy path: ${node.path}",
                CotaPaths.absolutePathOrNull(node.path) != null
            )
            if (!node.isDirectory) {
                assertEquals(node.path, node.file?.name)
            }
        }
    }

    @Test
    fun emptyInputBuildsAnEmptyTree() {
        assertTrue(CotaTree.build("thermal_config", emptyList()).isEmpty())
        assertTrue(CotaTree.build("", emptyList()).isEmpty())
    }

    @Test
    fun aFileThatIsAlsoAPrefixDoesNotCrashTheTreeBuilder() {
        // The real filesystem cannot contain both, but a hostile or corrupt
        // listing must never crash the settings screen.
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha", 1),
                file("thermal_config/alpha/one.xml", 2)
            )
        )

        val alpha = nodes.child("alpha")
        assertTrue(alpha.isDirectory)
        assertEquals(listOf("one.xml"), alpha.children.names())
        assertEquals(2L, alpha.totalSizeBytes)
    }

    @Test
    fun reverseOrderPrefixConflictAlsoKeepsTheDirectory() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha/one.xml", 2),
                file("thermal_config/alpha", 1)
            )
        )

        val alpha = nodes.child("alpha")
        assertTrue(alpha.isDirectory)
        assertEquals(listOf("one.xml"), alpha.children.names())
        assertEquals(2L, alpha.totalSizeBytes)
    }

    @Test
    fun invalidOrForeignPathsAreIgnoredWithoutCrashing() {
        listOf(
            "other/foreign.xml",
            "thermal_config//empty.xml",
            "thermal_config/./dot.xml",
            "thermal_config/../escape.xml",
            "thermal_config/trailing/"
        ).forEach { bad ->
            assertTrue(
                "unexpected node for $bad",
                CotaTree.build("thermal_config", listOf(file(bad))).isEmpty()
            )
        }
    }

    @Test
    fun rootGroupConvenienceBuildKeepsTopLevelFiles() {
        val group = CotaFileGroups.group(
            listOf(file("root.txt", 1))
        ).single()

        val nodes = CotaTree.build(group)

        assertEquals(listOf("root.txt"), nodes.names())
        assertEquals("root.txt", nodes.child("root.txt").path)
    }

    @Test
    fun duplicatePathsKeepTheLastEntry() {
        val nodes = CotaTree.build(
            groupKey = "thermal_config",
            files = listOf(
                file("thermal_config/alpha/one.xml", 1),
                file("thermal_config/alpha/one.xml", 9)
            )
        )

        assertEquals(9L, nodes.child("alpha").totalSizeBytes)
    }
}
