package com.nemu.hibernate.core

/** Parsers for `appops get` and `am get-standby-bucket` output. */
object RestrictionParsers {
    private val appOpsMode = Regex(
        """RUN_ANY_IN_BACKGROUND\s*[:=]\s*([A-Za-z_]+)""",
        RegexOption.IGNORE_CASE
    )
    private val bucketNumber = Regex("""\b(\d{1,3})\b""")

    fun parseAppOpsMode(output: String): String? {
        val match = appOpsMode.find(output) ?: return null
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
