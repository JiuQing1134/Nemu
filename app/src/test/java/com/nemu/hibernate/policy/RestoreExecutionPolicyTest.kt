package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreExecutionPolicyTest {
    @Test
    fun previewSkipsResidualCleanup() {
        assertFalse(RestoreExecutionPolicy.runsResidualCleanup(dryRun = true))
    }

    @Test
    fun actualRunRunsResidualCleanup() {
        assertTrue(RestoreExecutionPolicy.runsResidualCleanup(dryRun = false))
    }

    @Test
    fun previewLabelMakesSkippedWorkExplicit() {
        assertEquals(
            "安全残留：预览不执行",
            RestoreExecutionPolicy.residualLabel(dryRun = true)
        )
    }
}
