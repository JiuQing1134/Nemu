package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudStatusTest {
    @Test
    fun disable_user_status_uses_enabled_flag() {
        assertEquals(
            "已启用，进程未知",
            CloudStatus(enabled = true, suspended = false).describe(CloudAction.DISABLE_USER)
        )
        assertEquals(
            "已停用，进程未知",
            CloudStatus(enabled = false, suspended = false).describe(CloudAction.DISABLE_USER)
        )
    }

    @Test
    fun suspend_status_uses_both_flags() {
        assertEquals("未暂停，进程未知", CloudStatus(true, false).describe(CloudAction.SUSPEND))
        assertEquals("已暂停，进程未知", CloudStatus(true, true).describe(CloudAction.SUSPEND))
        assertEquals(
            "已暂停，已停用，进程未知",
            CloudStatus(false, true).describe(CloudAction.SUSPEND)
        )
    }

    @Test
    fun suspend_status_can_distinguish_a_live_process() {
        assertEquals(
            "已暂停，进程存活",
            CloudStatus(true, true, processRunning = true).describe(CloudAction.SUSPEND)
        )
    }

    @Test
    fun observe_only_uses_suspend_text() {
        assertEquals("未暂停，进程未知", CloudStatus(true, false).describe(CloudAction.OBSERVE_ONLY))
    }

    @Test
    fun disabled_and_not_suspended_reads_clearly() {
        assertEquals(
            "未暂停，已停用，进程未知",
            CloudStatus(false, false).describe(CloudAction.SUSPEND)
        )
    }
}
