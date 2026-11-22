package com.nemu.hibernate.core

object ShellQuote {
    fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
