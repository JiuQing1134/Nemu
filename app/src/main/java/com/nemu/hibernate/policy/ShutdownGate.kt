package com.nemu.hibernate.policy

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Allows exactly one shutdown-unfreeze pass per delivery window. The manifest
 * receiver and the service receiver can both be delivered on this OEM; the
 * loser must neither execute commands nor overwrite the winner's result, even
 * if it arrives just after the first pass finished.
 */
internal class ShutdownGate(
    private val duplicateWindowMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val running = AtomicBoolean(false)
    private val lastFinishedAt = AtomicLong(Long.MIN_VALUE)

    fun <T> run(action: () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        val now = clock()
        val last = lastFinishedAt.get()
        if (duplicateWindowMs > 0 &&
            last != Long.MIN_VALUE &&
            now - last in 0 until duplicateWindowMs
        ) {
            running.set(false)
            return null
        }
        return try {
            action()
        } finally {
            lastFinishedAt.set(clock())
            running.set(false)
        }
    }
}
