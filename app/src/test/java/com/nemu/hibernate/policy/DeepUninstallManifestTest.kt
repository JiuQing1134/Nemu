package com.nemu.hibernate.policy

import com.nemu.hibernate.core.DeepBackupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepUninstallManifestTest {
    private fun backup(
        packageName: String = "com.example.app",
        label: String = "Example",
        versionCode: Long = 42L,
        versionName: String? = "1.2.3",
        dir: String = "${DeepUninstallManifest.APK_ROOT}/com.example.app/42-1700000000000",
        files: List<String> = listOf("base.apk", "split_0.apk"),
        timestampMillis: Long = 1_700_000_000_000L,
        systemApp: Boolean = false
    ): DeepBackupInfo = DeepBackupInfo(
        packageName = packageName,
        label = label,
        versionCode = versionCode,
        versionName = versionName,
        dir = dir,
        files = files,
        timestampMillis = timestampMillis,
        systemApp = systemApp
    )

    @Test
    fun roundtrip_preserves_user_app_fields() {
        val info = backup()
        assertEquals(
            info,
            DeepUninstallManifest.decode(DeepUninstallManifest.encode(info))
        )
    }

    @Test
    fun roundtrip_preserves_system_flag_and_large_version_code() {
        val info = backup(versionCode = 4_294_967_296L, systemApp = true)
        assertEquals(
            info,
            DeepUninstallManifest.decode(DeepUninstallManifest.encode(info))
        )
    }

    @Test
    fun downloadDirMirrorsOurApkRootOnly() {
        val primary = "${DeepUninstallManifest.APK_ROOT}/com.example.app/42-1"
        assertEquals(
            "${DeepUninstallManifest.DOWNLOAD_APK_ROOT}/com.example.app/42-1",
            DeepUninstallManifest.downloadDir(primary)
        )
        assertEquals(
            "/tmp/not-ours",
            DeepUninstallManifest.downloadDir("/tmp/not-ours")
        )
    }

    @Test
    fun system_flag_is_encoded_as_zero_or_one() {
        val systemLine = DeepUninstallManifest.encode(backup(systemApp = true))
        val userLine = DeepUninstallManifest.encode(backup(systemApp = false))
        assertEquals("1", systemLine.split('\t')[7])
        assertEquals("0", userLine.split('\t')[7])
    }

    @Test
    fun null_version_name_is_dash_and_decodes_to_null() {
        val line = DeepUninstallManifest.encode(backup(versionName = null))
        assertEquals("-", line.split('\t')[3])
        val decoded = DeepUninstallManifest.decode(line)
        assertNotNull(decoded)
        assertNull(decoded!!.versionName)
    }

    @Test
    fun blank_version_name_decodes_to_null() {
        val line = "com.x\tX\t1\t\t/dir\t1\tbase.apk\t0"
        val decoded = DeepUninstallManifest.decode(line)
        assertNotNull(decoded)
        assertNull(decoded!!.versionName)
    }

    @Test
    fun special_characters_in_label_and_version_name_are_flattened() {
        val info = backup(label = "A\tB\nC|D", versionName = "v\t1|beta")
        val line = DeepUninstallManifest.encode(info)
        assertEquals(8, line.split('\t').size)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('|'))
        val decoded = DeepUninstallManifest.decode(line)
        assertNotNull(decoded)
        assertEquals("A B C D", decoded!!.label)
        assertEquals("v 1 beta", decoded.versionName)
    }

    @Test
    fun file_names_with_commas_are_sanitized() {
        val info = backup(files = listOf("base,apk", "split_0.apk"))
        val decoded = DeepUninstallManifest.decode(
            DeepUninstallManifest.encode(info)
        )
        assertNotNull(decoded)
        assertEquals(listOf("base_apk", "split_0.apk"), decoded!!.files)
        assertTrue(decoded.files.none { it.contains(',') })
    }

    @Test
    fun decode_all_skips_malformed_lines() {
        val validOne = DeepUninstallManifest.encode(
            backup(packageName = "com.one", timestampMillis = 1L)
        )
        val validTwo = DeepUninstallManifest.encode(
            backup(packageName = "com.two", timestampMillis = 2L)
        )
        val text = listOf(
            validOne,
            "",
            "not-a-manifest-line",
            "com.x\tX\twrong\t-\t/dir\t1\tbase.apk\t0",
            "com.x\tX\t1\t-\t/dir\t1\tbase.apk\t9",
            validTwo
        ).joinToString("\n")

        val decoded = DeepUninstallManifest.decodeAll(text)
        assertEquals(
            listOf("com.one", "com.two"),
            decoded.map { it.packageName }
        )
    }

    @Test
    fun blank_text_decodes_to_empty_list() {
        assertTrue(DeepUninstallManifest.decodeAll("").isEmpty())
        assertTrue(DeepUninstallManifest.decodeAll("\n\n").isEmpty())
    }

    @Test
    fun decode_rejects_wrong_field_count() {
        assertNull(DeepUninstallManifest.decode("a\tb\tc\td\te\tf\tg"))
        assertNull(DeepUninstallManifest.decode("a\tb\tc\td\te\tf\tg\th\ti"))
        assertNull(DeepUninstallManifest.decode(""))
    }

    @Test
    fun decode_rejects_bad_numbers_and_flags() {
        // Bad versionCode.
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\tnope\t-\t/dir\t1\tbase.apk\t0"
            )
        )
        // Bad timestamp.
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t/dir\tlater\tbase.apk\t0"
            )
        )
        // Bad system flag.
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t/dir\t1\tbase.apk\ttrue"
            )
        )
    }

    @Test
    fun decode_rejects_blank_required_fields() {
        // Blank package name.
        assertNull(
            DeepUninstallManifest.decode(
                "\tX\t1\t-\t/dir\t1\tbase.apk\t0"
            )
        )
        // Blank backup dir.
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t\t1\tbase.apk\t0"
            )
        )
        // Empty file list.
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t/dir\t1\t\t0"
            )
        )
    }

    @Test
    fun decode_rejects_file_names_with_path_separators() {
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t/dir\t1\t../base.apk\t0"
            )
        )
        assertNull(
            DeepUninstallManifest.decode(
                "com.x\tX\t1\t-\t/dir\t1\ta/b.apk\t0"
            )
        )
    }

    @Test
    fun decode_accepts_crlf_lines() {
        val line = DeepUninstallManifest.encode(backup())
        assertNotNull(DeepUninstallManifest.decode("$line\r"))
        val text = line + "\r\n" + DeepUninstallManifest.encode(
            backup(packageName = "com.two")
        ) + "\r\n"
        assertEquals(2, DeepUninstallManifest.decodeAll(text).size)
    }

    @Test
    fun replace_same_package_keeps_one_entry_and_order() {
        val first = backup(packageName = "com.a", versionCode = 1L, timestampMillis = 1L)
        val other = backup(packageName = "com.b", versionCode = 2L, timestampMillis = 2L)
        val replacement = backup(
            packageName = "com.a",
            versionCode = 3L,
            timestampMillis = 3L
        )

        val result = DeepUninstallManifest.replace(
            listOf(
                DeepUninstallManifest.encode(first),
                DeepUninstallManifest.encode(other)
            ),
            replacement
        )

        assertEquals(2, result.size)
        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
        assertEquals(3L, result[0].versionCode)
        assertEquals(2L, result[1].versionCode)
    }

    @Test
    fun replace_appends_a_new_package() {
        val first = backup(packageName = "com.a")
        val second = backup(packageName = "com.b")
        val result = DeepUninstallManifest.replace(
            listOf(DeepUninstallManifest.encode(first)),
            second
        )
        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }

    @Test
    fun replace_drops_bad_lines_and_keeps_valid_entries() {
        val first = backup(packageName = "com.a")
        val second = backup(packageName = "com.b")
        val result = DeepUninstallManifest.replace(
            listOf("garbage", DeepUninstallManifest.encode(first), ""),
            second
        )
        assertEquals(listOf("com.a", "com.b"), result.map { it.packageName })
    }

    @Test
    fun repeated_replace_keeps_one_entry_per_package() {
        val first = backup(packageName = "com.a", versionCode = 1L, timestampMillis = 1L)
        val second = backup(packageName = "com.a", versionCode = 2L, timestampMillis = 2L)
        val third = backup(packageName = "com.a", versionCode = 3L, timestampMillis = 3L)

        var entries = DeepUninstallManifest.replace(emptyList(), first)
        entries = DeepUninstallManifest.replace(
            entries.map { encodeLine(it) },
            second
        )
        entries = DeepUninstallManifest.replace(
            entries.map { encodeLine(it) },
            third
        )

        assertEquals(1, entries.size)
        assertEquals(3L, entries[0].versionCode)
    }

    @Test
    fun encode_all_roundtrips_and_ends_with_newline() {
        val first = backup(packageName = "com.a")
        val second = backup(packageName = "com.b")
        val text = DeepUninstallManifest.encodeAll(listOf(first, second))
        assertTrue(text.endsWith("\n"))
        assertEquals(
            listOf("com.a", "com.b"),
            DeepUninstallManifest.decodeAll(text).map { it.packageName }
        )
        assertEquals("", DeepUninstallManifest.encodeAll(emptyList()))
    }

    private fun encodeLine(info: DeepBackupInfo): String =
        DeepUninstallManifest.encode(info)
}
