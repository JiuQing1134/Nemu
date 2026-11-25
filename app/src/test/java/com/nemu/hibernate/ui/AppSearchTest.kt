package com.nemu.hibernate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {

    private val plainLatin = mapOf(
        "系统更新" to "xi tong geng xin",
        "应用市场" to "ying yong shi chang",
        "System Update" to "system update"
    )

    private val latinOf: (String) -> String = { label -> plainLatin[label].orEmpty() }

    private fun matches(
        label: String,
        query: String,
        packageName: String = "com.example.app"
    ): Boolean = matchesAppQuery(label, packageName, query, latinOf)

    @Test
    fun substringMatchesLabelAndPackageIgnoringCase() {
        assertTrue(matches("系统更新", "系统"))
        assertTrue(matches("System Update", "system"))
        assertTrue(
            matches(
                label = "系统更新",
                query = "UPDATE",
                packageName = "com.android.systemupdate"
            )
        )
    }

    @Test
    fun fullPinyinMatchesSyllablesAndWholeName() {
        assertTrue(matches("系统更新", "xitong"))
        assertTrue(matches("系统更新", "gengxin"))
        assertTrue(matches("系统更新", "xitonggengxin"))
        assertTrue(matches("应用市场", "shichang"))
        assertTrue(matches("应用市场", "yingyongshichang"))
    }

    @Test
    fun initialsMatch() {
        assertTrue(matches("系统更新", "xtgx"))
        assertTrue(matches("应用市场", "yysc"))
    }

    @Test
    fun queryIsNormalizedForCaseSpacesAndSeparators() {
        assertTrue(matches("系统更新", "XTGX"))
        assertTrue(matches("系统更新", "Xi Tong"))
        assertTrue(matches("系统更新", " xi-tong "))
        assertTrue(matches("系统更新", "geng_xin"))
        assertTrue(matches("系统更新", "xitong geng xin"))
    }

    @Test
    fun englishLabelsMatchByWordsAndInitials() {
        assertTrue(matches("System Update", "system update"))
        assertTrue(matches("System Update", "systemupdate"))
        assertTrue(matches("System Update", "update"))
        assertTrue(matches("System Update", "su"))
    }

    @Test
    fun toneMarkedTransliterationIsFoldedToPlainPinyin() {
        val tones: (String) -> String = { label ->
            if (label == "相机") "xiàng jī" else ""
        }

        assertTrue(matchesAppQuery("相机", "com.example.app", "xiangji", tones))
        assertTrue(matchesAppQuery("相机", "com.example.app", "xiang ji", tones))
        assertTrue(matchesAppQuery("相机", "com.example.app", "xj", tones))
    }

    @Test
    fun initialsHandleToneMarkedFirstLetters() {
        val tones: (String) -> String = { label ->
            if (label == "安全中心") "ān quán zhōng xīn" else ""
        }

        assertTrue(matchesAppQuery("安全中心", "com.example.app", "aqzx", tones))
    }

    @Test
    fun polyphonicWordsAlsoMatchTheReadingUsersType() {
        val polyphonic = mapOf(
            "QQ音乐" to "QQ yin le",
            "音乐" to "yin le",
            "中国银行" to "zhong guo yin xing",
            "银行" to "yin xing",
            "高德地图" to "gao de de tu",
            "地图" to "de tu",
            "长城" to "zhang cheng",
            "长沙" to "zhang sha",
            "绿色" to "lu se",
            "女士" to "nu shi",
            "旅行" to "lu xing"
        )
        val latin: (String) -> String = { polyphonic[it].orEmpty() }

        assertTrue(matchesAppQuery("QQ音乐", "com.qq.music", "yinyue", latin))
        assertTrue(matchesAppQuery("QQ音乐", "com.qq.music", "qqyy", latin))
        assertTrue(matchesAppQuery("中国银行", "com.bank", "yinhang", latin))
        assertTrue(matchesAppQuery("中国银行", "com.bank", "zgyh", latin))
        assertTrue(matchesAppQuery("高德地图", "com.map", "ditu", latin))
        assertTrue(matchesAppQuery("高德地图", "com.map", "gddt", latin))
        assertTrue(matchesAppQuery("长城", "com.gw", "changcheng", latin))
        assertTrue(matchesAppQuery("长沙", "com.cs", "changsha", latin))
        assertTrue(matchesAppQuery("绿色", "com.green", "lvse", latin))
        assertTrue(matchesAppQuery("女士", "com.lady", "nvshi", latin))
        assertTrue(matchesAppQuery("旅行", "com.trip", "lvxing", latin))
    }

    @Test
    fun queryFoldsToneMarksFullWidthAndSymbols() {
        assertTrue(matches("系统更新", "xì tǒng"))
        assertTrue(matches("系统更新", "ＸＴＧＸ"))
        assertTrue(matches("系统更新", "系统+更新"))
        assertTrue(matches("系统更新", "geng+xin"))
        assertTrue(matches("Cafe", "café"))
    }

    @Test
    fun unrelatedQueriesDoNotMatch() {
        assertFalse(matches("系统更新", "zzz"))
        assertFalse(matches("系统更新", "yingyong"))
        assertFalse(matches("系统更新", "update"))
    }

    @Test
    fun emptyBlankOrSeparatorOnlyQueryMatchesEverything() {
        assertTrue(matches("系统更新", ""))
        assertTrue(matches("系统更新", "   "))
        assertTrue(matches("系统更新", "\t\n"))
        assertTrue(matches("系统更新", " -_.· "))
    }

    @Test
    fun specialCharactersAreHandledWithoutCrashing() {
        assertFalse(matches("系统更新", "😀"))
        assertFalse(matches("系统更新", "系統"))
        assertFalse(matches("系统更新", "更新系统"))
        assertTrue(matches("系统更新", "系统 更新！"))
    }

    @Test
    fun throwingLatinResolverFallsBackToSubstringMatching() {
        val throwing: (String) -> String = { throw IllegalStateException("icu unavailable") }

        assertTrue(matchesAppQuery("系统更新", "com.example.app", "系统", throwing))
        assertTrue(
            matchesAppQuery(
                "系统更新",
                "com.example.systemupdate",
                "systemupdate",
                throwing
            )
        )
        assertFalse(matchesAppQuery("系统更新", "com.example.app", "xitong", throwing))
    }

    @Test
    fun latinResolverCachesTransliterationPerLabel() {
        var calls = 0
        val cached = memoizingLatinOf { label ->
            calls += 1
            if (label == "系统更新") "xi tong geng xin" else ""
        }

        assertTrue(matchesAppQuery("系统更新", "com.example.app", "xitong", cached))
        assertTrue(matchesAppQuery("系统更新", "com.example.app", "xtgx", cached))
        assertTrue(matchesAppQuery("系统更新", "com.example.app", "系统", cached))
        assertEquals(1, calls)
    }

    @Test
    fun latinResolverCachesFailureAndKeepsSubstringMatching() {
        var calls = 0
        val failing = memoizingLatinOf {
            calls += 1
            throw IllegalStateException("icu unavailable")
        }

        assertFalse(matchesAppQuery("系统更新", "com.example.app", "xitong", failing))
        assertFalse(matchesAppQuery("系统更新", "com.example.app", "gengxin", failing))
        assertEquals(1, calls)
        assertTrue(matchesAppQuery("系统更新", "com.example.app", "系统", failing))
        assertEquals(1, calls)
    }

    @Test
    fun packageLineIsHiddenWhenItRepeatsTheLabel() {
        assertFalse(shouldShowPackageName("com.example.app", "com.example.app"))
        assertFalse(shouldShowPackageName("COM.EXAMPLE.APP", "com.example.app"))
        assertTrue(shouldShowPackageName("相机", "com.example.app"))
        assertTrue(shouldShowPackageName("", "com.example.app"))
    }

    @Test
    fun appTitleDoesNotRepeatThePackageName() {
        assertEquals(
            "com.example.app",
            appTitle("com.example.app", "com.example.app")
        )
        assertEquals("com.example.app", appTitle("", "com.example.app"))
        assertEquals(
            "相机 · com.example.app",
            appTitle("相机", "com.example.app")
        )
    }
}
