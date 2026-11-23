package com.nemu.hibernate

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long apply/restore operations must not be cancelled by an accidental
 * rotation or system Back press. The activity keeps its composition for
 * configuration changes and blocks Back while a busy operation is running.
 */
class ActivityContinuityContractTest {
    @Test
    fun mainActivityHandlesRotationWithoutRecreation() {
        val manifest = TestSources.read("src/main/AndroidManifest.xml")
        val activity = manifest.substringAfter("android:name=\".MainActivity\"")
            .substringBefore("</activity>")

        assertTrue(
            "MainActivity 必须在旋转/尺寸变化时保留组合作用域",
            Regex(
                "android:configChanges=\"[^\"]*orientation[^\"]*screenSize[^\"]*\""
            ).containsMatchIn(activity)
        )
    }

    @Test
    fun backIsBlockedWhileBusyOperationRuns() {
        val main = TestSources.read(
            "src/main/java/com/nemu/hibernate/MainActivity.kt"
        )

        assertTrue(
            "忙碌时必须拦截返回，避免销毁 Activity 取消操作",
            main.contains("BackHandler(enabled = busy)")
        )
    }
}
