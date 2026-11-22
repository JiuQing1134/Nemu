package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test
    fun plain_value_is_single_quoted() {
        assertEquals(
            "'/data/cota/para/cpuconfig'",
            ShellQuote.quote("/data/cota/para/cpuconfig")
        )
    }

    @Test
    fun apostrophe_is_escaped() {
        assertEquals("'a'\\''b'", ShellQuote.quote("a'b"))
    }
}
