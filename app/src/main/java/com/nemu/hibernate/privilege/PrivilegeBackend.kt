package com.nemu.hibernate.privilege

import com.nemu.hibernate.core.ShellResult

interface PrivilegeBackend {
    val name: String

    /**
     * `pm hide` requires MANAGE_USERS, which only uid 0 holds on this build.
     * Shizuku in ADB mode (uid 2000) is denied; fakes default to false.
     */
    val canSetHidden: Boolean get() = false

    suspend fun isAvailable(): Boolean
    suspend fun exec(command: String): ShellResult
}
