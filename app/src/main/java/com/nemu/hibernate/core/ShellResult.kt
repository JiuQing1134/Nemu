package com.nemu.hibernate.core

data class ShellResult(
    val exitCode: Int,
    val output: String
) {
    val ok: Boolean get() = exitCode == 0
}
