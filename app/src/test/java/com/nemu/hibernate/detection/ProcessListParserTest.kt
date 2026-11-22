package com.nemu.hibernate.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessListParserTest {
    @Test
    fun parsesProcessNamesAndMatchesOnlyExactPackageOrSubprocess() {
        val snapshot = ProcessListParser.parse(
            "PID ARGS\n" +
                " 4707 com.hihonor.hiview\n" +
                "17122 com.hihonor.magicvoice:interactor\n" +
                "17130 com.hihonor.magicvoice2\n"
        )!!

        assertTrue(snapshot.isRunning("com.hihonor.hiview"))
        assertTrue(snapshot.isRunning("com.hihonor.magicvoice"))
        assertTrue(snapshot.isRunning("com.hihonor.magicvoice2"))
        assertFalse(snapshot.isRunning("com.hihonor.missing"))
        assertFalse(
            ProcessListSnapshot(setOf("com.hihonor.magicvoice2"))
                .isRunning("com.hihonor.magicvoice")
        )
    }

    @Test
    fun skipsHeaderAndUnrelatedRows() {
        val snapshot = ProcessListParser.parse(
            "PID ARGS\n" +
                "1 /init\n" +
                "sh -c ps -A -o PID,ARGS\n" +
                " 204 com.example.target:worker extra-arg\n"
        )!!

        assertEquals(setOf("/init", "com.example.target:worker"), snapshot.processNames)
        assertTrue(snapshot.isRunning("com.example.target"))
    }

    @Test
    fun rejectsOutputWithNoPidRows() {
        assertNull(ProcessListParser.parse("PID ARGS\nnot a process list"))
    }
}
