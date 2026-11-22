package com.nemu.hibernate.privilege

/**
 * Marker for backends running as uid 0. COTA file operations accept only this
 * type. Shizuku runs as the shell uid (2000) and cannot read /data/cota, so it
 * deliberately does not implement this interface.
 */
interface RootPrivilegeBackend : PrivilegeBackend
