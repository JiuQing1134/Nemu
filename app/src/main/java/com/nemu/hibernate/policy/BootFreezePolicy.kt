package com.nemu.hibernate.policy

/**
 * Inputs for the pure boot-freeze decision. Every value comes from
 * [com.nemu.hibernate.data.BootFreezeSettings] / the boot count reader; this
 * type has no Android dependency so it is fully unit-testable.
 */
data class BootFreezeInput(
    val bootFreezeEnabled: Boolean,
    val bootCount: Int,
    val lastSkippedBootCount: Int = -1,
    val lastFreezeBootCount: Int = -1,
    val lastAttemptBootCount: Int = -1,
    val lastAttemptTime: Long = 0L,
    val lastFreezeSucceeded: Boolean = false,
    val lastFreezeTime: Long = 0L,
    val lastShutdownTime: Long = 0L,
    val lastShutdownOk: Boolean = false
)

data class BootFreezeDecision(
    val shouldRun: Boolean,
    val reason: String
)

/** Combined App/root-guard shutdown evidence for the boot-freeze gate. */
data class BootShutdownGate(
    val time: Long,
    val clean: Boolean
)

/**
 * Pure bootloop-guard policy (design docs/07 §5.3).
 *
 * The automatic freeze only runs when:
 * 1. the master switch is on;
 * 2. this boot has not been handled yet (freeze decision recorded);
 * 3. the user did not manually skip this boot;
 * 4. this boot has not already started an attempt;
 * 5. any previous freeze attempt is followed by a confirmed clean shutdown.
 *
 * Rule 5 is deliberately fail-closed for the immediately previous boot: if
 * the previous boot started a freeze attempt and no clean shutdown was
 * observed after it, the automatic freeze stays off for this boot. A skipped
 * boot records its own decision, so the boot after it may retry even when the
 * earlier attempt was unclean; the guard must not disable auto-freeze forever.
 */
object BootFreezePolicy {
    /**
     * Merges the App's verified clean-shutdown record with the root guard's
     * planned-shutdown epoch. Both inputs and the result are milliseconds.
     * A planned shutdown only proves cleanliness when it happened at/after the
     * last freeze attempt; an older planned record must not unlock the gate.
     */
    fun shutdownGate(
        plannedShutdownMs: Long,
        appShutdownOk: Boolean,
        appShutdownTimeMs: Long,
        lastAttemptTimeMs: Long
    ): BootShutdownGate = BootShutdownGate(
        time = maxOf(appShutdownTimeMs, plannedShutdownMs),
        clean = appShutdownOk ||
            (plannedShutdownMs > 0 &&
                plannedShutdownMs >= lastAttemptTimeMs)
    )

    fun shouldRun(input: BootFreezeInput): Boolean = evaluate(input).shouldRun

    fun evaluate(input: BootFreezeInput): BootFreezeDecision {
        if (!input.bootFreezeEnabled) {
            return BootFreezeDecision(false, "开机自动冻结未开启")
        }
        // A negative count means the platform did not expose a boot-stable
        // identity. Fail open rather than treating every boot as the same one.
        if (input.bootCount >= 0) {
            if (input.bootCount == input.lastFreezeBootCount) {
                return BootFreezeDecision(false, "本次开机已处理过开机冻结")
            }
            if (input.bootCount == input.lastSkippedBootCount) {
                return BootFreezeDecision(false, "用户已跳过本次开机的自动冻结")
            }
            if (input.bootCount == input.lastAttemptBootCount) {
                return BootFreezeDecision(false, "本次开机已尝试过开机冻结")
            }
        }

        // Only the immediately previous boot counts. An attempt from two or
        // more boots ago (typically followed by a guarded skip) must not
        // suspend automatic freeze permanently.
        val hasPriorAttempt = input.bootCount >= 0 &&
            input.lastAttemptBootCount == input.bootCount - 1
        val cleanShutdownAfterAttempt =
            input.lastShutdownOk && input.lastShutdownTime >= input.lastAttemptTime
        if (hasPriorAttempt && !cleanShutdownAfterAttempt) {
            return BootFreezeDecision(
                false,
                "上次冻结尝试后未检测到干净关机"
            )
        }

        return BootFreezeDecision(true, "允许执行开机冻结")
    }
}
