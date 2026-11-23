package com.nemu.hibernate.core

/**
 * Chooses the strategy used for restore. This deliberately uses the stored
 * strategy, not the currently-supported strategy: a capability may be retired
 * in a newer build while an older build left the package disabled/hidden.
 */
object RestoreStrategySelector {
    fun select(
        stored: FreezeStrategy,
        ownsHidden: Boolean,
        ownsDisable: Boolean,
        disabledNow: Boolean
    ): FreezeStrategy = when {
        ownsHidden -> FreezeStrategy.HIDE
        stored != FreezeStrategy.DISABLE_USER &&
            ownsDisable &&
            disabledNow -> FreezeStrategy.DISABLE_USER
        else -> stored
    }
}
