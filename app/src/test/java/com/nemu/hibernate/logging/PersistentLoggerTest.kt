package com.nemu.hibernate.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class PersistentLoggerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("logs")
        PersistentLogger.initForTest(root, enabled = false)
    }

    @After
    fun tearDown() {
        PersistentLogger.initForTest(
            temporaryFolder.newFolder("teardown"),
            enabled = false
        )
    }

    @Test
    fun disabledWritesNothing() {
        PersistentLogger.log("INFO", "startup", "should not be written")

        assertFalse(PersistentLogger.isEnabled)
        assertTrue(PersistentLogger.files().isEmpty())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun enabledWritesFormattedLine() {
        PersistentLogger.initForTest(root, enabled = true)

        PersistentLogger.log("INFO", "startup", "pid=1 boot=2 version=0.1.0")

        assertTrue(PersistentLogger.isEnabled)
        val files = PersistentLogger.files()
        assertEquals(1, files.size)
        assertTrue(files[0].name.matches(Regex("""nemu-\d{8}\.log""")))
        val line = files[0].readLines().single()
        val expected = Regex(
            """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2}) """ +
                """INFO +\[startup] pid=1 boot=2 version=0\.1\.0$"""
        )
        assertTrue("unexpected line: $line", expected.matches(line))
    }

    @Test
    fun setEnabledTogglesWriting() {
        PersistentLogger.log("INFO", "test", "off")
        assertTrue(PersistentLogger.files().isEmpty())

        PersistentLogger.setEnabled(true)
        PersistentLogger.log("INFO", "test", "on")
        assertEquals(1, PersistentLogger.recentLines(10).size)

        PersistentLogger.setEnabled(false)
        PersistentLogger.log("INFO", "test", "off-again")
        assertEquals(1, PersistentLogger.recentLines(10).size)
    }

    @Test
    fun recentLinesReturnsLastLines() {
        PersistentLogger.initForTest(root, enabled = true)

        repeat(5) { PersistentLogger.log("INFO", "test", "line-$it") }

        val lines = PersistentLogger.recentLines(3)
        assertEquals(3, lines.size)
        assertTrue(lines.last().endsWith("line-4"))
    }

    @Test
    fun recentLinesReturnsEmptyWhenNothingWritten() {
        PersistentLogger.initForTest(root, enabled = true)

        assertTrue(PersistentLogger.recentLines(100).isEmpty())
    }

    @Test
    fun recentLinesSpansRotatedFilesInOrder() {
        PersistentLogger.initForTest(root, enabled = true)
        val older = File(root, "nemu-20250101.log")
        older.writeText("old-1\nold-2\n")
        assertTrue(older.setLastModified(System.currentTimeMillis() - 10_000))
        val newer = File(root, "nemu-20250102.log")
        newer.writeText("new-1\n")
        assertTrue(newer.setLastModified(System.currentTimeMillis() - 5_000))

        val lines = PersistentLogger.recentLines(2)

        assertEquals(listOf("old-2", "new-1"), lines)
    }

    @Test
    fun rotationRollsOverAtTwoMegabytes() {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val current = File(root, "nemu-$today.log")
        current.writeBytes(
            ByteArray(2 * 1024 * 1024 - 16) { 'a'.code.toByte() }
        )
        PersistentLogger.initForTest(root, enabled = true)

        PersistentLogger.log("INFO", "test", "after-rotation")

        val names = root.listFiles()!!.map { it.name }
        assertTrue(
            "expected rotated file in $names",
            names.any {
                it.matches(Regex("""nemu-$today\.\d+\.log"""))
            }
        )
        assertTrue(current.exists())
        assertTrue("current file not restarted: ${current.length()}", current.length() < 1024)
        assertTrue(
            root.listFiles()!!.all { it.length() <= 2L * 1024 * 1024 }
        )
    }

    @Test
    fun retentionKeepsAtMostSevenFiles() {
        val old = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        repeat(10) { index ->
            val file = File(root, "nemu-202501%02d.log".format(index + 1))
            file.writeText("old\n")
            assertTrue(file.setLastModified(old - index * 1000L))
        }
        PersistentLogger.initForTest(root, enabled = true)

        PersistentLogger.log("INFO", "test", "newest")

        val files = PersistentLogger.files()
        assertEquals(7, files.size)
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        assertTrue(files.any { it.name == "nemu-$today.log" })
    }

    @Test
    fun startupCapturesArePrunedToThreeFiles() {
        PersistentLogger.initForTest(root, enabled = true)
        repeat(5) { index ->
            val file = File(root, "startup-20250101-12000$index.txt")
            file.writeText("capture-$index")
            assertTrue(file.setLastModified(System.currentTimeMillis() + index * 1000L))
        }

        PersistentLogger.log("INFO", "test", "trigger retention")

        assertEquals(
            3,
            root.listFiles()!!.count { it.name.startsWith("startup-") }
        )
    }

    @Test
    fun clearDeletesLogFiles() {
        PersistentLogger.initForTest(root, enabled = true)
        PersistentLogger.log("INFO", "test", "one")
        File(root, "startup-20250101-120000.txt").writeText("logcat")

        PersistentLogger.clear()

        assertTrue(PersistentLogger.files().isEmpty())
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test
    fun filesIgnoresForeignFiles() {
        PersistentLogger.initForTest(root, enabled = true)
        File(root, "not-a-log.txt").writeText("keep me")

        PersistentLogger.log("INFO", "test", "hello")

        val files = PersistentLogger.files()
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("nemu-"))
        assertTrue(File(root, "not-a-log.txt").exists())
    }

    @Test
    fun longMessagesAreTruncated() {
        PersistentLogger.initForTest(root, enabled = true)

        PersistentLogger.log("INFO", "test", "x".repeat(5_000))

        val line = PersistentLogger.recentLines(1).single()
        assertTrue("line too long: ${line.length}", line.length < 1_100)
    }
}
