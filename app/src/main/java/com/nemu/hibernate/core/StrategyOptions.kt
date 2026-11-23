package com.nemu.hibernate.core

/**
 * UI-level preset derived from the persisted five-value [FreezeStrategy] plus
 * [StrategyOptions]. Presets are never persisted, so the on-disk strategy
 * remains compatible with older builds.
 */
enum class StrategyPreset {
    OBSERVE,
    PAUSE,
    FREEZE,
    COMPONENT,
    DISABLE_USER,
    HIDE,
    RESTRICT_BACKGROUND,
    RESTRICT_STANDBY
}

/**
 * Orthogonal modifiers for a target. Only [forceStop] and [components] apply to
 * the freeze strategies; [appOps]/[standby] are persistent background
 * restrictions and may stand alone with [FreezeStrategy.OBSERVE].
 *
 * A null [components] means "not configured"; callers must fail closed for
 * COMPONENT instead of treating it as "all registered components". Migration
 * materializes the legacy all-components set explicitly.
 */
data class StrategyOptions(
    val schema: Int = 1,
    val forceStop: Boolean = true,
    val components: Set<String>? = null,
    val appOps: Boolean = false,
    val standby: Boolean = false,
    val backend: String? = null
) {
    fun presetOf(strategy: FreezeStrategy): StrategyPreset = when {
        strategy == FreezeStrategy.SUSPEND && forceStop -> StrategyPreset.FREEZE
        strategy == FreezeStrategy.SUSPEND -> StrategyPreset.PAUSE
        strategy == FreezeStrategy.COMPONENT -> StrategyPreset.COMPONENT
        strategy == FreezeStrategy.DISABLE_USER -> StrategyPreset.DISABLE_USER
        strategy == FreezeStrategy.HIDE -> StrategyPreset.HIDE
        strategy == FreezeStrategy.OBSERVE && appOps -> StrategyPreset.RESTRICT_BACKGROUND
        strategy == FreezeStrategy.OBSERVE && standby -> StrategyPreset.RESTRICT_STANDBY
        else -> StrategyPreset.OBSERVE
    }
}

/**
 * Versioned, pure-Kotlin codec for [StrategyOptions]. Android's org.json is not
 * available in JVM unit tests, so the format is a deliberately small
 * key=value list separated by '|'. Component class names cannot contain '|',
 * ',' or '=', which keeps the encoding unambiguous.
 */
object StrategyOptionsCodec {
    private const val VERSION = 1

    fun encode(options: StrategyOptions): String {
        val parts = mutableListOf<String>()
        parts += "v=${options.schema}"
        parts += "forceStop=${options.forceStop}"
        parts += "components=" + (
            options.components?.sorted()?.joinToString(",") ?: "null"
            )
        parts += "appOps=${options.appOps}"
        parts += "standby=${options.standby}"
        options.backend?.takeIf { it.isNotBlank() }?.let { parts += "backend=$it" }
        return parts.joinToString("|")
    }

    fun decode(raw: String?): StrategyOptions? {
        if (raw.isNullOrBlank()) return null
        val fields = linkedMapOf<String, String>()
        for (part in raw.split('|')) {
            val separator = part.indexOf('=')
            if (separator <= 0) return null
            val key = part.substring(0, separator)
            if (fields.containsKey(key)) return null
            fields[key] = part.substring(separator + 1)
        }

        val schema = fields["v"]?.toIntOrNull() ?: return null
        if (schema != VERSION) return null

        val forceStop = fields.boolean("forceStop", default = true) ?: return null
        val appOps = fields.boolean("appOps", default = false) ?: return null
        val standby = fields.boolean("standby", default = false) ?: return null

        val components = when (val value = fields["components"] ?: "null") {
            "null" -> null
            "" -> emptySet()
            else -> value.split(',')
                .filter { it.isNotBlank() }
                .toSet()
        }

        return StrategyOptions(
            schema = schema,
            forceStop = forceStop,
            components = components,
            appOps = appOps,
            standby = standby,
            backend = fields["backend"]?.takeIf { it.isNotBlank() }
        )
    }

    private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean? {
        if (!containsKey(key)) return default
        return this[key]?.toBooleanStrictOrNull()
    }
}
