package com.nemu.hibernate.ui

import com.nemu.hibernate.core.Target
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caches the last *actual* package-state snapshot and serialises refreshes.
 *
 * Why: every selection/tab interaction used to start its own read of the whole
 * catalog (PackageManager lookups + a root process list, and in some paths a
 * `dumpsys package` per HIDE candidate). A burst of taps therefore executed
 * several overlapping shell reads and the redraw had to wait for one of them.
 *
 * The cache makes redraws pure state reads:
 * - [peek] returns the last snapshot without touching shell/PackageManager;
 * - [read] serves a fresh snapshot when the requested packages are covered;
 * - a forced read always probes, because it follows a real freeze/restore and
 *   must never report the pre-action state; callers serialise bursts with a
 *   single-flight queue instead of relying on a time window here;
 * - partial refreshes (e.g. only applied targets when entering the Unfreeze
 *   page) merge into the full snapshot, so the catalog stays complete.
 *
 * Nothing here decides freeze/restore behaviour; it only shortens the UI path.
 */
class TargetActualCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val mutex = Mutex()

    @Volatile
    private var snapshot: Map<String, TargetActualUiState> = emptyMap()

    @Volatile
    private var coveredPackages: Set<String> = emptySet()

    @Volatile
    private var lastReadAt: Long = Long.MIN_VALUE

    /** Last snapshot, safe to read from the UI thread. */
    fun peek(): Map<String, TargetActualUiState> = snapshot

    /** True when every requested package is covered by a non-expired read. */
    fun isFresh(targets: List<Target>): Boolean {
        val age = ageMillis()
        return age <= ttlMillis &&
            targets.all { it.packageName in coveredPackages }
    }

    /**
     * Returns an actual-state snapshot for [targets].
     *
     * @param force probe again even when the cache is still fresh. Forced
     * reads come from explicit refresh or a completed freeze/restore and must
     * observe the new state; callers merge bursts through their single-flight
     * queue before reaching this method.
     * @param reader performs the real probe; called with the requested subset.
     */
    suspend fun read(
        targets: List<Target>,
        force: Boolean,
        advanceTtl: Boolean = true,
        reader: suspend (List<Target>) -> Map<String, TargetActualUiState>
    ): Map<String, TargetActualUiState> = mutex.withLock {
        val requested = targets.map { it.packageName }.toSet()
        val age = ageMillis()
        val covered = requested.all { it in coveredPackages }
        val fresh = age <= ttlMillis && covered

        if (!force && fresh) {
            return snapshot
        }
        // A non-forced request that the snapshot does not cover (e.g. a newly
        // added custom target) falls through and reads the requested subset;
        // the result is merged into the full snapshot.
        val result = reader(targets)
        val merged = snapshot.toMutableMap()
        requested.forEach { packageName ->
            val value = result[packageName]
            if (value != null) {
                merged[packageName] = value
            } else {
                // The package is gone (uninstalled/hidden with no reader entry)
                // or the probe failed for it; do not keep a stale claim.
                merged.remove(packageName)
            }
        }
        snapshot = merged
        coveredPackages = coveredPackages + requested
        if (advanceTtl) lastReadAt = clock()
        snapshot
    }

    private fun ageMillis(): Long {
        val now = clock()
        return if (lastReadAt == Long.MIN_VALUE) Long.MAX_VALUE else now - lastReadAt
    }

    companion object {
        /** Redraws never query; this only bounds how stale a background read may be. */
        const val DEFAULT_TTL_MILLIS = 20_000L
    }
}
