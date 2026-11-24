package com.nemu.hibernate.policy

import com.nemu.hibernate.privilege.RootPrivilegeBackend
import com.nemu.hibernate.core.FreezeStrategy
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.core.TargetComponent

data class GuardHealth(
    val installed: Boolean,
    val hasUnhide: Boolean,
    val hasAppOps: Boolean,
    val hasStandby: Boolean
) {
    val ready: Boolean
        get() = installed && hasUnhide && hasAppOps && hasStandby
}

object GuardHealthParser {
    fun parse(output: String): GuardHealth = GuardHealth(
        installed = output.contains("NEMU_GUARD_PRESENT"),
        hasUnhide = output.contains("NEMU_UNHIDE"),
        hasAppOps = output.contains("NEMU_APPOPS"),
        hasStandby = output.contains("NEMU_STANDBY")
    )
}

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
    private const val HIDDEN_MARKER_DIR = "$GUARD_DIR/hidden_by_nemu"
    private const val APPOPS_MARKER_DIR = "$GUARD_DIR/appops_owned"
    private const val STANDBY_MARKER_DIR = "$GUARD_DIR/standby_owned"
    private const val SUSPEND_MARKER_DIR = "$GUARD_DIR/suspended_by_nemu"
    /**
     * Packages the guard may re-enable. Every catalog package is eligible;
     * the marker itself is still required, so the guard never touches a
     * package Nemu did not own.
     */
    private val disableTargets: Set<String>
        get() = TargetCatalog.all.map { it.packageName }.toSet()

    private const val CLEAR_COMMAND =
        "/data/adb/nemu/boot_guard/boot_guard.sh clear"

    /** Checks the installed guard script for the recovery capabilities we use. */
    suspend fun health(backend: RootPrivilegeBackend?): GuardHealth {
        if (backend == null) {
            return GuardHealth(false, false, false, false)
        }
        val script = "$GUARD_DIR/boot_guard.sh"
        val command =
            "[ -f '$script' ] && echo NEMU_GUARD_PRESENT; " +
                "grep -q 'pm unhide' '$script' 2>/dev/null && echo NEMU_UNHIDE; " +
                "grep -q 'RUN_ANY_IN_BACKGROUND' '$script' 2>/dev/null && " +
                "echo NEMU_APPOPS; " +
                "grep -q 'set-standby-bucket' '$script' 2>/dev/null && " +
                "echo NEMU_STANDBY"
        return GuardHealthParser.parse(backend.exec(command).output)
    }

    /**
     * Three-state rescue check. Null means the marker could not be read
     * (no root backend or shell failure) — callers that execute freeze
     * commands must fail closed and retry/skip instead of assuming false.
     */
    suspend fun rescueState(backend: RootPrivilegeBackend?): Boolean? {
        if (backend == null) return null
        val result = backend.exec(
            "[ -f $RESCUE_FLAG ] && echo NEMU_RESCUE || true"
        )
        if (!result.ok) return null
        return result.output.contains("NEMU_RESCUE")
    }

    suspend fun isRescue(backend: RootPrivilegeBackend?): Boolean =
        rescueState(backend) == true

    /** Marks a successful normal unfreeze so quick clean reboots do not count as failures. */
    suspend fun markCleanShutdown(backend: RootPrivilegeBackend?): Boolean {
        if (backend == null) return false
        // Keep one unconsumed marker in a .prev slot, and publish the new one
        // atomically. A boot where the guard did not run must not make the
        // next shutdown overwrite the only clean-shutdown record.
        val command = "mkdir -p '$GUARD_DIR' && " +
            "new=\$(cat /proc/sys/kernel/random/boot_id 2>/dev/null); " +
            "[ -n \"\$new\" ] || exit 1; " +
            "old=\$(cat '$GUARD_DIR/clean_shutdown' 2>/dev/null); " +
            "if [ -n \"\$old\" ] && [ \"\$old\" != \"\$new\" ]; then " +
            "cp '$GUARD_DIR/clean_shutdown' " +
            "'$GUARD_DIR/clean_shutdown.prev' && " +
            "chmod 600 '$GUARD_DIR/clean_shutdown.prev'; fi; " +
            "tmp=\"$GUARD_DIR/.clean_shutdown.\$\$\"; " +
            "printf '%s\\n' \"\$new\" > \"\$tmp\" && " +
            "chmod 600 \"\$tmp\" && " +
            "mv -f \"\$tmp\" '$GUARD_DIR/clean_shutdown'"
        return backend.exec(command).ok
    }

    /**
     * Epoch of the previous boot's planned shutdown or of its successful
     * sys.boot_completed, as classified by the root guard. Both prove the
     * previous boot was not a boot-screen hang. Returns null when the guard
     * did not run this boot, the record is stale, or the boot was unclean.
     */
    suspend fun lastPlannedShutdownTime(
        backend: RootPrivilegeBackend?
    ): Long? {
        if (backend == null) return null
        // Emit the current boot id, a separator, then the guard's record:
        //   <observed-boot-id> <planned|clean|unclean> <previous> <epoch-s>
        // Parsing in Kotlin keeps the token contract unit-testable instead of
        // depending on `read` variable count and shell field splitting.
        val command =
            "cat /proc/sys/kernel/random/boot_id 2>/dev/null; " +
                "echo NEMU_SEPARATOR; " +
                "cat '$GUARD_DIR/last_shutdown' 2>/dev/null"
        val result = backend.exec(command)
        if (!result.ok) return null
        val parts = result.output.split("NEMU_SEPARATOR", limit = 2)
        if (parts.size != 2) return null
        val currentBootId = parts[0].trim()
        val fields = parts[1].trim().split(Regex("\\s+"))
        if (currentBootId.isEmpty() || fields.size != 4) return null
        if (fields[0] != currentBootId ||
            (fields[1] != "planned" && fields[1] != "completed")
        ) {
            return null
        }
        // The guard writes epoch seconds; all App timestamps are milliseconds.
        return fields[3].toLongOrNull()?.times(1000L)
    }

    /** The boot rescue only re-enables disable-user changes Nemu owns. */
    suspend fun markDisabledByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        disabledByNemu: Boolean
    ): Boolean {
        if (backend == null) return false
        // Only these cloud targets ever get a disable-user marker; removing a
        // marker for any other package is a no-op and must not wedge cleanup.
        if (disabledByNemu && packageName !in disableTargets) return false
        if (!disabledByNemu && packageName !in disableTargets) return true
        val marker = com.nemu.hibernate.core.ShellQuote.quote(
            "$DISABLED_MARKER_DIR/$packageName"
        )
        val command = if (disabledByNemu) {
            "mkdir -p '$DISABLED_MARKER_DIR' && touch $marker && chmod 600 $marker"
        } else {
            "rm -f $marker"
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
            val payload = com.nemu.hibernate.core.ShellQuote.quote(
                "${component.className}|${originalState ?: 0}"
            )
            "mkdir -p '$COMPONENT_MARKER_DIR' && " +
                "printf '%s' $payload > '$marker' && " +
                "chmod 600 '$marker'"
        } else {
            "rm -f '$marker'"
        }
        return backend.exec(command).ok
    }

    /** Records a package hidden by Nemu so rescue can `pm unhide` it. */
    suspend fun markHiddenByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        hiddenByNemu: Boolean
    ): Boolean {
        if (backend == null) return false
        val marker = com.nemu.hibernate.core.ShellQuote.quote(
            "$HIDDEN_MARKER_DIR/$packageName"
        )
        val command = if (hiddenByNemu) {
            "mkdir -p '$HIDDEN_MARKER_DIR' && touch $marker && " +
                "chmod 600 $marker"
        } else {
            "rm -f $marker"
        }
        return backend.exec(command).ok
    }

    /**
     * Marks a package Nemu suspended, so a normal boot restores only Nemu's
     * own suspensions and never a Hail/MDM/user one. Written before
     * `pm suspend`; removed only after a verified unsuspend.
     */
    suspend fun markSuspendedByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        suspendedByNemu: Boolean
    ): Boolean {
        if (backend == null) return false
        val marker = com.nemu.hibernate.core.ShellQuote.quote(
            "$SUSPEND_MARKER_DIR/$packageName"
        )
        val command = if (suspendedByNemu) {
            "mkdir -p '$SUSPEND_MARKER_DIR' && touch $marker && chmod 600 $marker"
        } else {
            "rm -f $marker"
        }
        return backend.exec(command).ok
    }

    /** Records the original AppOps mode so rescue restores the exact value. */
    suspend fun markAppOpsByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        owned: Boolean,
        originalMode: String? = null
    ): Boolean {
        if (backend == null) return false
        val marker = com.nemu.hibernate.core.ShellQuote.quote(
            "$APPOPS_MARKER_DIR/$packageName"
        )
        val command = if (owned) {
            val payload = com.nemu.hibernate.core.ShellQuote.quote(
                originalMode ?: "unknown"
            )
            "mkdir -p '$APPOPS_MARKER_DIR' && " +
                "printf '%s' $payload > $marker && chmod 600 $marker"
        } else {
            "rm -f $marker"
        }
        return backend.exec(command).ok
    }

    /** Records the original standby bucket so rescue restores the exact value. */
    suspend fun markStandbyByNemu(
        backend: RootPrivilegeBackend?,
        packageName: String,
        owned: Boolean,
        originalBucket: Int? = null
    ): Boolean {
        if (backend == null) return false
        val marker = com.nemu.hibernate.core.ShellQuote.quote(
            "$STANDBY_MARKER_DIR/$packageName"
        )
        val command = if (owned) {
            "mkdir -p '$STANDBY_MARKER_DIR' && " +
                "printf '%s' '${originalBucket ?: 0}' > $marker && " +
                "chmod 600 $marker"
        } else {
            "rm -f $marker"
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
