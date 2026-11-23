package com.nemu.hibernate.policy

import com.nemu.hibernate.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Broadcast receivers must not block the main thread, and only the gate owner
 * may write the shutdown result. Both delivery paths share one background
 * worker instead of racing each other's start/finish writes.
 */
class ShutdownExecutionContractTest {
    @Test
    fun shutdownWorkRunsOnABackgroundDispatcher() {
        val source = TestSources.read(
            "src/main/java/com/nemu/hibernate/policy/ShutdownSafety.kt"
        )

        assertTrue(
            "关机解冻必须在后台线程执行",
            source.contains("CoroutineScope(Dispatchers.IO).launch")
        )
        assertTrue(
            "重复路径必须被单一的 ShutdownGate 拦截",
            source.contains("ShutdownGate(")
        )
    }

    @Test
    fun receiversDelegateToSharedAsyncWorker() {
        listOf(
            "src/main/java/com/nemu/hibernate/policy/ShutdownReceiver.kt",
            "src/main/java/com/nemu/hibernate/policy/SafetyService.kt"
        ).forEach { path ->
            val source = TestSources.read(path)

            assertTrue("$path 必须使用 ShutdownSafety.runAsync", source.contains("ShutdownSafety.runAsync"))
            assertFalse("$path 不得在主线程直接运行阻塞解冻", source.contains("runBlockingUnfreeze"))
            assertFalse("$path 不得自行改写关机结果", source.contains("markShutdownFinished"))
            assertFalse("$path 不得自行重置关机开始标记", source.contains("markShutdownStarted"))
        }
    }
}
