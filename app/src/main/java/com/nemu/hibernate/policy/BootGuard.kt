package com.nemu.hibernate.policy

import com.nemu.hibernate.privilege.RootPrivilegeBackend
import com.nemu.hibernate.core.CloudTargets
import com.nemu.hibernate.core.AntiFraudTargets
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetComponent

/**
 * App-side view of the independent boot guard (`docs/09`).
 *
 * The guard scripts live in `/data/adb` and are not owned by this app; these
 * helpers only read/clear the rescue marker through the current root backend.
 * No command is attempted when [backend] is null.
 */
object BootGuard {
    const val RESCUE_FLAG = "/data/adb/nemu/boot_guard/rescue.flag"
    private const val GUARD_DIR = "/data/adb/nemu/boot_guard"
    private const val DISABLED_MARKER_DIR = "$GUARD_DIR/disabled_by_nemu"
    private const val COMPONENT_MARKER_DIR = "$GUARD_DIR/owned_components"
    /**
     * Packages the guard may re-enable. Only targets whose catalog strategy
     * permits whole-package disable-user are included; the marker itself is
     * still required, so the guard never touches a package Nemu did not own.
     */
    private val disableTargets: Set<String>
        get() = (CloudTargets.disableUserAllowed + AntiFraudTargets.NUMBER_IDENTITY +
            TargetCatalog.all
                .filter { FreezeStrategy.DISABLE_USER in it.supportedStrategies }
                .map { it.packageName })
            .distinct()
            .toSet()

    private const val CLEAR_COMMAND =
        "/data/adb/nemu/boot_guard/boot_guard.sh clear"

    suspend fun isRescue(backend: RootPrivilegeBackend?): Boolean {
        if (backend == null) return false
        val result = backend.exec(
            "[ -f $RESCUE_FLAG ] && echo NEMU_RESCUE || true"
        )
        return result.ok && result.output.contains("NEMU_RESCUE")
    }

    /** Marks a successful normal unfreeze so quick clean reboots do not count as failures. */
    suspend fun markCleanShutdown(backend: RootPrivilegeBackend?): Boolean {
        if (backend == null) return false
        val command = "mkdir -p '$GUARD_DIR' && " +
            "cat /proc/sys/kernel/random/boot_id > '$GUARD_DIR/clean_shutdown' && " +
            "chmod 600 '$GUARD_DIR/clean_shutdown'"
        return backend.exec(command).ok
    }

    /** The boot rescue only re-enables disable-user changes Nemu owns. */
    suspend fun markDisabledByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        disabledByNemu: Boolean
    ): Boolean {
        if (backend == null || packageName !in disableTargets) return false
        val marker = "$DISABLED_MARKER_DIR/$packageName"
        val command = if (disabledByNemu) {
            "mkdir -p '$DISABLED_MARKER_DIR' && touch '$marker' && chmod 600 '$marker'"
        } else {
            "rm -f '$marker'"
        }
        return backend.exec(command).ok
    }

    /** Records a component disabled by Nemu so boot rescue can restore it. */
    suspend fun markComponentDisabled(
        backend: RootPrivilegeBackend?,
        component: TargetComponent,
        disabled: Boolean,
        originalState: Int? = null
    ): Boolean {
        if (backend == null) return false
        val name = component.className.hashCode().toUInt().toString(16)
        val marker = "$COMPONENT_MARKER_DIR/$name"
        val command = if (disabled) {
            // Persist the exact pre-freeze state (0=default, 1=enabled) so the
            // guard can use pm enable for a component that was explicitly
            // enabled and default-state otherwise.
            val payload = "${component.className}|${originalState ?: 0}"
            "mkdir -p '$COMPONENT_MARKER_DIR' && " +
                "printf '%s' '$payload' > '$marker' && " +
                "chmod 600 '$marker'"
        } else {
            "rm -f '$marker'"
        }
        return backend.exec(command).ok
    }

    /**
     * User-confirmed exit from rescue mode. The module `disable` file is
     * removed, so the next boot may execute Nemu's module again. The app does
     * not auto-freeze anything after clearing.
     */
    suspend fun clear(backend: RootPrivilegeBackend?): Boolean {
        if (backend == null) return false
        return backend.exec(CLEAR_COMMAND).ok
    }
}
