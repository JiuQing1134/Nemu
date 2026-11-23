package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandBuilderTest {
    @Test
    fun suspend_uses_user_zero_and_quotes_package() {
        assertEquals(
            "pm suspend --user 0 'com.hihonor.ouc'",
            CommandBuilder.suspend("com.hihonor.ouc")
        )
    }

    @Test
    fun unsuspend_uses_user_zero_and_quotes_package() {
        assertEquals(
            "pm unsuspend --user 0 'com.hihonor.ouc'",
            CommandBuilder.unsuspend("com.hihonor.ouc")
        )
    }

    @Test
    fun forceStop_has_no_user_argument() {
        assertEquals(
            "am force-stop 'com.hihonor.ouc'",
            CommandBuilder.forceStop("com.hihonor.ouc")
        )
    }

    @Test
    fun disableUser_quotes_package() {
        assertEquals(
            "pm disable-user --user 0 'com.hihonor.regservice'",
            CommandBuilder.disableUser("com.hihonor.regservice")
        )
    }

    @Test
    fun appOpsBackground_uses_run_any_in_background() {
        assertEquals(
            "appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND ignore",
            CommandBuilder.appOpsBackground("com.hihonor.securityserver", restricted = true)
        )
        assertEquals(
            "appops set --user 0 'com.hihonor.securityserver' RUN_ANY_IN_BACKGROUND allow",
            CommandBuilder.appOpsBackground("com.hihonor.securityserver", restricted = false)
        )
    }

    @Test
    fun standbyBucket_uses_restricted_and_active() {
        assertEquals(
            "am set-standby-bucket --user 0 'com.hihonor.awareness' restricted",
            CommandBuilder.standbyBucket("com.hihonor.awareness", restricted = true)
        )
        assertEquals(
            "am set-standby-bucket --user 0 'com.hihonor.awareness' active",
            CommandBuilder.standbyBucket("com.hihonor.awareness", restricted = false)
        )
    }

    @Test
    fun unsuspendAll_joins_quoted_packages_in_one_command() {
        assertEquals(
            "pm unsuspend --user 0 'com.hihonor.ouc' 'com.honor.yoyoappsug'",
            CommandBuilder.unsuspendAll(
                listOf("com.hihonor.ouc", "com.honor.yoyoappsug")
            )
        )
    }
}
