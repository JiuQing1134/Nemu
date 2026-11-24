package com.nemu.hibernate.core

/** Parsers for `appops get` and `am get-standby-bucket` output. */
object RestrictionParsers {
    // AOSP prints UID-level entries first as
    // "Uid mode: RUN_ANY_IN_BACKGROUND: ignore", then the package entry.
    // Anchor at the line start so the UID line is never mistaken for the
    // package mode; the value token stops at whitespace/;/ ( .
    private val appOpsMode = Regex(
        """^RUN_ANY_IN_BACKGROUND\s*[:=]\s*(?:mode=)?([A-Za-z_]+)""",
        RegexOption.IGNORE_CASE
    )
    private val bucketNumber = Regex("""\b(\d{1,3})\b""")

    fun parseAppOpsMode(output: String): String? {
        val match = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Uid mode:", ignoreCase = true) }
            .firstNotNullOfOrNull { appOpsMode.find(it) }
            ?: return null
        return match.groupValues[1].lowercase()
    }

    fun parseStandbyBucket(output: String): Int? {
        if (output.contains("error", ignoreCase = true) ||
            output.contains("not found", ignoreCase = true)
        ) {
            return null
        }
        val match = bucketNumber.findAll(output).lastOrNull() ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
