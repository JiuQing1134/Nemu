package com.nemu.hibernate.ui

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Matching for the app search boxes in the freeze / unfreeze / all-apps
 * screens.
 *
 * A row matches when its label or package name contains the query, ignoring
 * case, whitespace and common separators, or when the label's pinyin matches
 * the query either as plain syllables ("xitong", "gengxin", "xitonggengxin")
 * or as initials ("xtgx").
 *
 * Pinyin handling is pure Kotlin: callers pass [latinOf], which turns a label
 * into a Latin transliteration such as "xi tong geng xin". The Android entry
 * point below supplies an ICU-backed implementation; unit tests pass a plain
 * map. If transliteration is unavailable or throws, matching safely degrades
 * to the original substring behaviour instead of failing.
 */

private const val LATIN_TRANSLITERATOR_ID = "Han-Latin; Latin-ASCII; Lower"

/**
 * ICU Han-Latin uses one reading per polyphonic character. These word-level
 * aliases add the reading users actually type for common app-name words
 * (音乐/银行/地图/长城/…). They are supplements, not a replacement for ICU:
 * an unknown polyphone still falls back to the ICU reading.
 *
 * Values are space-separated syllables so both the full form ("yinyue") and
 * initials ("yy", or "qqyy" inside "QQ音乐") match.
 */
private val PINYIN_WORD_ALIASES: Map<String, String> = linkedMapOf(
    "音乐" to "yin yue",
    "银行" to "yin hang",
    "地图" to "di tu",
    "长城" to "chang cheng",
    "长沙" to "chang sha",
    "长春" to "chang chun",
    "重庆" to "chong qing",
    "绿色" to "lv se",
    "女士" to "nv shi",
    "旅行" to "lv xing",
    "行业" to "hang ye",
    "重要" to "zhong yao",
    "重复" to "chong fu",
    "重新" to "chong xin",
    "觉得" to "jue de",
    "睡觉" to "shui jiao",
    "方便" to "fang bian",
    "便宜" to "pian yi",
    "调查" to "diao cha",
    "调整" to "tiao zheng",
    "角色" to "jue se",
    "角度" to "jiao du",
    "参加" to "can jia",
    "人参" to "ren shen",
    "节省" to "jie sheng",
    "省会" to "sheng hui",
    "厦门" to "xia men",
    "西藏" to "xi zang",
    "朝阳" to "chao yang"
)

/**
 * Resolved lazily so that unit tests which only use the [latinOf] overload
 * never touch android.icu (whose JVM stubs throw).
 */
private val hanLatinTransliterator: Transliterator? by lazy {
    runCatching { Transliterator.getInstance(LATIN_TRANSLITERATOR_ID) }.getOrNull()
}

private fun transliterateToLatin(label: String): String {
    val transliterator = hanLatinTransliterator ?: return ""
    // android.icu Transliterator instances are not thread-safe.
    return synchronized(transliterator) {
        transliterator.transliterate(label).orEmpty()
    }
}

private val productionLatinOf: (String) -> String = memoizingLatinOf(::transliterateToLatin)

/**
 * Production entry point. Transliteration results are cached per label so a
 * keystroke only pays the ICU cost for labels never seen before.
 */
fun matchesAppQuery(label: String, packageName: String, query: String): Boolean =
    matchesAppQuery(label, packageName, query, productionLatinOf)

/**
 * Testable matching core. [latinOf] may throw or return an empty string; in
 * that case only the label / package name substring checks apply.
 */
internal fun matchesAppQuery(
    label: String,
    packageName: String,
    query: String,
    latinOf: (String) -> String
): Boolean {
    val needle = normalizeForSearch(query)
    if (needle.isEmpty()) {
        // Blank/separator-only queries show everything, but an emoji-only
        // query must not: it contains no searchable character at all.
        // “·” and other Unicode punctuation still count as separators.
        return query.none { ch ->
            !ch.isWhitespace() &&
                !ch.isLetterOrDigit() &&
                !isSearchPunctuation(ch)
        }
    }
    if (normalizeForSearch(label).contains(needle)) return true
    if (normalizeForSearch(packageName).contains(needle)) return true
    // Pinyin is ASCII-only, so a query with CJK or emoji cannot match it.
    if (needle.any { it.code > 0x7F }) return false

    val latin = runCatching { latinOf(label) }.getOrDefault("")
    if (latin.isEmpty()) return false
    return pinyinVariants(label, latin, latinOf).any { variant ->
        fullPinyin(variant).contains(needle) ||
            initialsPinyin(variant).contains(needle)
    }
}

/**
 * Original transliteration plus one variant per known polyphonic word found
 * in the label. Capped so a pathological name cannot explode the search
 * cost; in practice a label contains zero or one such word.
 */
private fun pinyinVariants(
    label: String,
    latin: String,
    latinOf: (String) -> String
): List<String> {
    val variants = LinkedHashSet<String>()
    variants += latin
    for ((word, alias) in PINYIN_WORD_ALIASES) {
        if (!label.contains(word)) continue
        val wordLatin = runCatching { latinOf(word) }
            .getOrDefault("")
            .trim()
        if (wordLatin.isEmpty()) continue
        // Snapshot so variants built in this step are not fed to replace()
        // again by a later alias.
        for (base in variants.toList()) {
            if (!base.contains(wordLatin)) continue
            variants += base.replace(wordLatin, alias)
            if (variants.size >= 12) return variants.toList()
        }
    }
    return variants.toList()
}

/**
 * Wraps [compute] with a per-label cache. Failures are cached as an empty
 * string, which keeps the substring fallback in effect for that label.
 */
internal fun memoizingLatinOf(compute: (String) -> String): (String) -> String {
    val cache = ConcurrentHashMap<String, String>()
    return { label ->
        val cached = cache[label]
        if (cached != null) {
            cached
        } else {
            val value = runCatching { compute(label) }.getOrDefault("")
            cache.putIfAbsent(label, value) ?: value
        }
    }
}

/**
 * NFKC folds full-width Latin into ASCII, NFD splits accented letters, and
 * keeping only letters/digits drops every separator and combining mark. This
 * makes “café”, “ＣＡＦＥ”, “系统+更新” and “系统-更新” behave like “cafe” and
 * “系统更新” without maintaining a separator whitelist.
 */
private fun normalizeForSearch(raw: String): String {
    if (raw.isEmpty()) return raw
    val folded = Normalizer.normalize(
        Normalizer.normalize(raw, Normalizer.Form.NFKC),
        Normalizer.Form.NFD
    )
    val builder = StringBuilder(folded.length)
    for (ch in folded) {
        if (!ch.isLetterOrDigit()) continue
        builder.append(ch.lowercaseChar())
    }
    return builder.toString()
}

/**
 * "xi tong geng xin" -> "xitonggengxin". Tone marks and other non-ASCII
 * diacritics are removed through NFD decomposition, so both plain and
 * tone-marked transliterations work.
 */
private fun fullPinyin(latin: String): String {
    val decomposed = Normalizer.normalize(latin, Normalizer.Form.NFD)
    val builder = StringBuilder(decomposed.length)
    for (ch in decomposed) {
        val folded = foldAsciiAlphanumeric(ch)
        if (folded != null) builder.append(folded)
    }
    return builder.toString()
}

/**
 * "xi tong geng xin" -> "xtgx", one initial per whitespace-separated
 * syllable. A multi-letter token without a vowel is an acronym/brand
 * ("QQ", "5G"), where users type every letter: "QQ音乐" -> "qqyy".
 */
private fun initialsPinyin(latin: String): String {
    val decomposed = Normalizer.normalize(latin, Normalizer.Form.NFD)
    val builder = StringBuilder()
    val token = StringBuilder()

    fun flushToken() {
        if (token.isEmpty()) return
        val text = token.toString()
        val isAcronym = text.length > 1 && text.none { it in "aeiou" }
        if (isAcronym) {
            builder.append(text)
        } else {
            builder.append(text.first())
        }
        token.clear()
    }

    for (ch in decomposed) {
        if (ch.isWhitespace()) {
            flushToken()
            continue
        }
        foldAsciiAlphanumeric(ch)?.let { token.append(it) }
    }
    flushToken()
    return builder.toString()
}

private fun isSearchPunctuation(ch: Char): Boolean = when (
    Character.getType(ch)
) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt() -> true
    else -> false
}

private fun foldAsciiAlphanumeric(ch: Char): Char? = when (ch) {
    in 'a'..'z', in '0'..'9' -> ch
    in 'A'..'Z' -> ch.lowercaseChar()
    else -> null
}
