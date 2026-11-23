package com.nemu.hibernate.core

/**
 * Value stored under the historical `antifraud_numberidentity_mode` key.
 * [com.nemu.hibernate.policy.TargetPolicy] mirrors the number-identity
 * target's strategy here so modes and restore baselines written by older
 * builds still load. HIDE is a first-class mode: mapping it to OFF made a
 * successfully hidden package look unselected.
 */
enum class NumberIdentityMode {
    OFF,
    SUSPEND,
    DISABLE_USER,
    HIDE;

    fun toStrategy(): FreezeStrategy? = when (this) {
        OFF -> null
        SUSPEND -> FreezeStrategy.SUSPEND
        DISABLE_USER -> FreezeStrategy.DISABLE_USER
        HIDE -> FreezeStrategy.HIDE
    }

    companion object {
        fun fromStrategy(strategy: FreezeStrategy): NumberIdentityMode =
            when (strategy) {
                FreezeStrategy.DISABLE_USER -> DISABLE_USER
                FreezeStrategy.SUSPEND -> SUSPEND
                FreezeStrategy.HIDE -> HIDE
                else -> OFF
            }
    }
}
