package com.nemu.hibernate.core

/**
 * Shutdown timing budget. The grouped-restore planner was never wired into
 * production and has been removed; only this shared constant remains.
 */
object RestoreBatchPlan {
    /** Broadcast window is ~10s; leave headroom for the final marker write. */
    const val SHUTDOWN_BUDGET_MS = 8_000L
}
