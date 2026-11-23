package com.nemu.hibernate

import java.io.File

/** Reads checked-in sources regardless of whether Gradle runs in app/ or repo root. */
object TestSources {
    fun read(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("../$relative"),
            File("../../$relative")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
        return file.readText()
    }

    fun uiFiles(): Map<String, String> = listOf(
        "DashboardScreen.kt",
        "NemuScreens.kt",
        "DetectionScreen.kt",
        "SettingsEntries.kt",
        "CotaSection.kt"
    ).associateWith { name ->
        read("src/main/java/com/nemu/hibernate/ui/$name")
    }
}
