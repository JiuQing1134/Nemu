package com.nemu.hibernate.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ShutdownGateTest {
    @Test
    fun onlyTheFirstConcurrentRunExecutes() {
        val gate = ShutdownGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String?> {
                gate.run {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "first"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val second = executor.submit<String?> {
                gate.run {
                    secondRan.set(true)
                    "second"
                }
            }

            assertNull(second.get(5, TimeUnit.SECONDS))
            assertFalse("重复路径不得执行解冻", secondRan.get())

            release.countDown()
            assertEquals("first", first.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun aLaterRunCanExecuteAfterTheFirstFinishes() {
        val gate = ShutdownGate()

        assertEquals("first", gate.run { "first" })
        assertEquals("second", gate.run { "second" })
    }

    @Test
    fun sequentialDuplicateInsideTheWindowIsIgnored() {
        val clock = AtomicLong(1_000)
        val gate = ShutdownGate(
            duplicateWindowMs = 10_000,
            clock = { clock.get() }
        )

        assertEquals("first", gate.run { "first" })
        clock.set(5_000)
        assertNull(gate.run { "duplicate" })
        clock.set(12_000)
        assertEquals("later", gate.run { "later" })
    }
}
