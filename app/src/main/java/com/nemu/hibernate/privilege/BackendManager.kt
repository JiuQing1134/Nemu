package com.nemu.hibernate.privilege

import android.content.Context
import com.nemu.hibernate.data.Settings
import rikka.shizuku.Shizuku

class BackendManager(private val settings: Settings) {
    private val root = RootBackend()
    private val shutdownRoot = RootBackend(timeoutMs = 3_000)
    private val shizuku = ShizukuBackend()
    private val shutdownShizuku = ShizukuBackend(timeoutMs = 3_000)

    suspend fun get(): PrivilegeBackend? = when (settings.backend) {
        "root" -> root.takeIf { it.isAvailable() }
        "shizuku" -> shizuku.takeIf { it.isAvailable() }
        else -> when {
            root.isAvailable() -> root
            shizuku.isAvailable() -> shizuku
            else -> null
        }
    }

    /**
     * COTA/thermal is root-only. This never returns the Shizuku backend even
     * when Shizuku is available and selected as the general backend.
     */
    suspend fun getRoot(): RootPrivilegeBackend? =
        root.takeIf { it.isAvailable() }

    /** Root backend with a short command bound for shutdown-only marker writes. */
    suspend fun getRootForShutdown(): RootPrivilegeBackend? =
        shutdownRoot.takeIf { it.isAvailable() }

    /**
     * Shutdown path: use the same backend preference as [get] so Nemu clears
     * the suspension it created (Android records the suspending package).
     * Root is preferred when the user has not explicitly selected Shizuku.
     * The root fallback uses a shorter timeout than normal operations.
     */
    suspend fun getForShutdown(): PrivilegeBackend? {
        val order = when (settings.backend) {
            "shizuku" -> listOf<PrivilegeBackend>(shutdownShizuku, shutdownRoot)
            else -> listOf<PrivilegeBackend>(shutdownRoot, shutdownShizuku)
        }
        for (backend in order) {
            if (backend.isAvailable()) return backend
        }
        return null
    }

    /**
     * Current authorization mode, not a verbose "available" sentence. The
     * top bar and overview only need to tell the user which mode is active.
     */
    suspend fun status(): String = when {
        root.isAvailable() -> PrivilegeLabels.activeBackendLabel(
            rootAvailable = true,
            shizukuRunning = false,
            shizukuGranted = false,
            shizukuUid = null
        )
        shizuku.isAvailable() -> PrivilegeLabels.shizukuBackendLabel(
            runCatching { Shizuku.getUid() }.getOrNull()
        )
        runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
            "Shizuku（未授权）"
        else -> "未授权"
    }

    /**
     * Probe for a working root shell. On first use the root manager may show
     * its authorization prompt; that is the intended side effect of an
     * explicit refresh or of the "请求授权" action.
     */
    suspend fun rootAvailable(): Boolean = root.isAvailable()

    /** Full, plain-language privilege snapshot for the status UI. */
    suspend fun readPrivilegeOverview(context: Context): PrivilegeOverview =
        PrivilegeOverviewReader.read(context, this)

    /**
     * Requests root authorization from the manager (KernelSU/Magisk/APatch
     * decide whether to prompt). Returns true when root is already granted.
     */
    suspend fun requestRoot(): Boolean = root.isAvailable()
}
