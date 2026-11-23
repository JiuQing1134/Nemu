package com.nemu.hibernate.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class CotaCopyTest {
    @Test
    fun purposeExplainsWhatTheThermalModuleDoes() {
        assertTrue(CotaCopy.PURPOSE.contains("温控"))
        assertTrue(CotaCopy.PURPOSE.contains("备份"))
        assertTrue(CotaCopy.PURPOSE.contains("默认"))
    }

    @Test
    fun riskRequiresBackupBeforeDeleting() {
        assertTrue(CotaCopy.RISK.contains("删除"))
        assertTrue(CotaCopy.RISK.contains("备份"))
        assertTrue(CotaCopy.RISK.contains("充电"))
    }

    @Test
    fun offHintIsShortAndActionable() {
        assertTrue(CotaCopy.OFF_HINT.contains("开启"))
        assertTrue(CotaCopy.OFF_HINT.length < 30)
    }
}
