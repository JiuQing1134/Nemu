package com.nemu.hibernate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentPreferenceKeysTest {
    @Test
    fun originalBaselineAloneStillProvesOwnership() {
        assertEquals(
            listOf("com.example.app/com.example.Receiver"),
            ComponentPreferenceKeys.ownedNames(
                keys = listOf(
                    "strategy_component_original_com.example.app_" +
                        "com.example.app/com.example.Receiver"
                ),
                packageName = "com.example.app"
            )
        )
    }

    @Test
    fun ownedMarkerAndBaselineAreDeduplicated() {
        val className = "com.example.app/com.example.Receiver"
        val prefix = "strategy_component_owned_com.example.app_"
        val original = "strategy_component_original_com.example.app_"

        assertEquals(
            listOf(className),
            ComponentPreferenceKeys.ownedNames(
                keys = listOf(prefix + className, original + className),
                packageName = "com.example.app"
            )
        )
    }
}
