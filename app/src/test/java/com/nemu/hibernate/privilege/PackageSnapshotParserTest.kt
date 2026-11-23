package com.nemu.hibernate.privilege

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageSnapshotParserTest {
    private val hiddenLine =
        "    User 0: ceDataInode=8100 deDataInode=2998 installed=true hidden=true " +
            "suspended=false suspendedByPrivacy=false distractionFlags=0 stopped=false " +
            "iAwareStopped=false notLaunched=false enabled=0 instant=false virtual=false " +
            "quarantined=false"

    private val visibleLine =
        "    User 0: ceDataInode=8770 deDataInode=2613 installed=true hidden=false " +
            "suspended=false suspendedByPrivacy=false distractionFlags=0 stopped=false " +
            "iAwareStopped=false notLaunched=false enabled=0 instant=false virtual=false " +
            "quarantined=false"

    @Test
    fun parsesHiddenPackageWithOemExtraFields() {
        val snapshot = PackageSnapshotParser.parse(
            "Packages:\n  Package [com.example] (abc):\n$hiddenLine"
        )

        assertTrue(snapshot.exists)
        assertEquals(true, snapshot.installed)
        assertEquals(true, snapshot.hidden)
        assertEquals(false, snapshot.suspended)
        assertEquals(false, snapshot.stopped)
        assertEquals(0, snapshot.enabledState)
    }

    @Test
    fun parsesVisiblePackage() {
        val snapshot = PackageSnapshotParser.parse(visibleLine)

        assertEquals(false, snapshot.hidden)
        assertEquals(true, snapshot.installed)
    }

    @Test
    fun installedFalseCountsAsMissingForOwnershipCleanup() {
        val line = visibleLine.replace("installed=true", "installed=false")
        val snapshot = PackageSnapshotParser.parse(
            "Packages:\n  Package [com.example] (abc):\n$line"
        )

        assertTrue(snapshot.exists)
        assertEquals(false, snapshot.installed)
        assertTrue(snapshot.isMissing())
    }

    @Test
    fun missingPackageIsReportedAsNotExisting() {
        val snapshot = PackageSnapshotParser.parse(
            "Unable to find package: com.example.missing"
        )

        assertFalse(snapshot.exists)
        assertNull(snapshot.hidden)
    }

    @Test
    fun injectedUnableToFindInsideAPackageSectionIsNotMissing() {
        val snapshot = PackageSnapshotParser.parse(
            "Packages:\n  Package [com.example] (abc):\n" +
                "    versionName=Unable to find package: com.example\n" +
                "$visibleLine"
        )

        assertTrue(snapshot.exists)
        assertEquals(false, snapshot.hidden)
    }

    @Test
    fun duplicateUserStateLinesFailClosed() {
        val snapshot = PackageSnapshotParser.parse(
            "Packages:\n  Package [com.example] (abc):\n" +
                "$hiddenLine\n$visibleLine"
        )

        assertTrue(snapshot.exists)
        assertNull(snapshot.hidden)
        assertNull(snapshot.suspended)
    }

    @Test
    fun missingUserLineLeavesFieldsUnknownInsteadOfGuessing() {
        val snapshot = PackageSnapshotParser.parse(
            "Packages:\n  Package [com.example] (abc):\n    userId=10001"
        )

        assertTrue(snapshot.exists)
        assertNull(snapshot.installed)
        assertNull(snapshot.hidden)
    }
}
