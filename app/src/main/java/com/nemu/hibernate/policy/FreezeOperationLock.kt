package com.nemu.hibernate.policy

import kotlinx.coroutines.sync.Mutex

/**
 * Single-flight for destructive ownership operations in this process.
 *
 * WorkManager, the UI and the safety service all run in the same app process.
 * Without this lock a boot-safety residual pass can read "not suspended" in
 * the gap between an apply's marker write and its `pm suspend`, then erase the
 * marker of the freeze it was supposed to protect.
 */
internal object FreezeOperationLock {
    val mutex = Mutex()
}
