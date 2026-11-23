package com.nemu.hibernate.detection

import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.FreezeStrategyPolicy
import com.nemu.hibernate.core.NumberIdentityMode
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.policy.TargetPolicy

/**
 * Read-only strategy resolution for the Detection page. Must match
 * [TargetPolicy.strategy] so a legacy number-identity HIDE mode is not shown
 * as SUSPEND/inconsistent.
 */
object DetectionStrategy {
    fun resolve(
        target: Target,
        legacyNumberMode: String?,
        storedRaw: String?
    ): FreezeStrategy {
        if (target.packageName == TargetPolicy.NUMBER_IDENTITY) {
            val legacy = legacyNumberMode
                ?.let {
                    runCatching { NumberIdentityMode.valueOf(it) }.getOrNull()
                }
                ?.toStrategy()
            if (legacy != null &&
                FreezeStrategyPolicy.isAllowed(target, legacy)
            ) {
                return legacy
            }
        }
        val stored = storedRaw
            ?.let { runCatching { FreezeStrategy.valueOf(it) }.getOrNull() }
            ?: target.defaultStrategy
        return if (FreezeStrategyPolicy.isAllowed(target, stored)) {
            stored
        } else {
            target.defaultStrategy
        }
    }
}
