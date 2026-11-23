package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.Targets

/**
 * Every package Nemu may leave suspended across a process lifetime.
 * Called by both the shutdown and boot safety paths; this is the single source
 * of truth for the safety unfreeze set.
 *
 * Carrier DM targets are included because the unified Freeze page allows
 * SUSPEND for them. `pm unsuspend` on a disable-user package is a harmless
 * no-op and never enables it; disable-user recovery stays ownership-gated.
 */
object SafetyTargets {
    /**
     * Recomputed on each access so custom targets added at runtime are always
     * included in shutdown/boot safety.
     */
    val suspendPackageNames: List<String>
        get() = (Targets.all.map { it.packageName } +
            AiTargets.all.map { it.packageName } +
            // Every catalog target can be selected for SUSPEND; ownership is
            // checked by the caller before actually unsuspending.
            TargetCatalog.all.map { it.packageName })
            .distinct()
}
