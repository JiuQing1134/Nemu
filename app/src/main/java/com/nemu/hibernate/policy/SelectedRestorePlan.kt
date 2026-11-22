package com.nemu.hibernate.policy

import com.nemu.hibernate.core.AiTargets
import com.nemu.hibernate.core.CloudAction
import com.nemu.hibernate.core.CloudTarget
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.DenyList
import com.nemu.hibernate.core.Target
import com.nemu.hibernate.core.Targets

/** Routes a user's selected packages to the controller that owns each policy. */
data class SelectedRestorePlan(
    val core: List<Target>,
    val ai: List<Target>,
    val cloud: List<CloudTarget>
) {
    val isEmpty: Boolean get() = core.isEmpty() && ai.isEmpty() && cloud.isEmpty()

    val packageNames: Set<String>
        get() = (core.map { it.packageName } + ai.map { it.packageName } +
            cloud.map { it.packageName }).toSet()

    companion object {
        fun fromSelection(packageNames: Set<String>): SelectedRestorePlan =
            SelectedRestorePlan(
                core = Targets.all.filter {
                    it.packageName in packageNames && !DenyList.isProtected(it.packageName)
                },
                ai = AiTargets.all.filter { it.packageName in packageNames },
                cloud = CloudTargets.all.filter {
                    it.packageName in packageNames &&
                        it.action != CloudAction.OBSERVE_ONLY &&
                        !DenyList.isProtected(it.packageName)
                }
            )
    }
}
