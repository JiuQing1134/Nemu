package com.nemu.hibernate.cota

import com.nemu.hibernate.core.ShellResult
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CotaControllerTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private class FakeFile(
        val size: Long,
        var sha: String,
        val mode: String,
        val uid: Int,
        val gid: Int
    )

    private class FakeRootBackend : RootPrivilegeBackend {
        override val name: String = "FakeRoot"
        val commands = mutableListOf<String>()
        var dirExists = true
        val files = linkedMapOf(
            "cpuconfig" to FakeFile(1024, "a".repeat(64), "644", 1000, 1000)
        )
        var manifest: String? = null
        var backupHashes: Map<String, String> = emptyMap()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String): ShellResult {
            commands += command
            return when {
                command.startsWith("if [ -d '/data/cota/para'") ->
                    if (dirExists) {
                        ShellResult(
                            0,
                            files.keys.joinToString("\n") { "${CotaPaths.SOURCE_DIR}/$it" }
                        )
                    } else {
                        ShellResult(0, CotaPaths.DIR_MISSING)
                    }

                command.startsWith("stat -c") -> {
                    val paths = command.substringAfter("'%n|%s|%a|%u|%g' ")
                        .split(" ")
                        .map { it.trim('\'') }
                    val lines = paths.map { path ->
                        val file = files[path.substringAfterLast('/')]
                            ?: return ShellResult(1, "missing")
                        "$path|${file.size}|${file.mode}|${file.uid}|${file.gid}"
                    }
                    ShellResult(0, lines.joinToString("\n"))
                }

                command.startsWith("sha256sum '/data/cota/para") -> {
                    val paths = command.removePrefix("sha256sum ")
                        .split(" ")
                        .map { it.trim('\'') }
                    val lines = paths.map { path ->
                        val file = files[path.substringAfterLast('/')]
                            ?: return ShellResult(1, "missing")
                        "${file.sha}  $path"
                    }
                    ShellResult(0, lines.joinToString("\n"))
                }

                command.startsWith("mkdir -p") -> ShellResult(0, "")

                command.startsWith("sha256sum '/storage/emulated/0/Nemu") -> {
                    val paths = command.removePrefix("sha256sum ")
                        .split(" ")
                        .map { it.trim('\'') }
                    val lines = paths.map { path ->
                        val name = path.substringAfterLast('/')
                        val sha = backupHashes[name]
                            ?: return ShellResult(1, "missing backup")
                        "$sha  $path"
                    }
                    ShellResult(0, lines.joinToString("\n"))
                }

                command.startsWith("printf '%s'") -> {
                    val encoded = command.substringAfter("printf '%s' '")
                        .substringBefore("' |")
                    manifest = String(
                        Base64.getDecoder().decode(encoded),
                        Charsets.UTF_8
                    )
                    ShellResult(0, "")
                }

                command.startsWith("cat ") -> {
                    val text = manifest
                    if (text == null) ShellResult(1, "missing") else ShellResult(0, text)
                }

                command.startsWith("rm -f ") -> {
                    command.removePrefix("rm -f ")
                        .split(" ")
                        .map { it.trim('\'') }
                        .forEach { files.remove(it.substringAfterLast('/')) }
                    ShellResult(0, "")
                }

                command.startsWith("[ -d '/data/cota/para' ]") ->
                    ShellResult(0, if (dirExists) "yes" else "no")

                command.startsWith("cp -p '/storage/emulated/0/Nemu") -> {
                    backupHashes.forEach { (name, sha) ->
                        files[name] = FakeFile(1024, sha, "644", 1000, 1000)
                    }
                    ShellResult(0, "")
                }

                command.startsWith("cp -p ") -> ShellResult(0, "")
                command.startsWith("chown ") || command.startsWith("chmod ") ->
                    ShellResult(0, "")
                command.startsWith("find '/storage/emulated/0/Nemu") ->
                    ShellResult(0, "")
                command.startsWith("find '/data/cota/para'") -> ShellResult(0, "")
                else -> ShellResult(0, "")
            }
        }
    }

    private fun controller(
        backend: FakeRootBackend?,
        enabled: Boolean = true,
        clock: () -> String = { "20261121-183000" }
    ) = CotaController(
        backend = backend,
        enabled = enabled,
        backupRoot = "/storage/emulated/0/Nemu/cota-backup",
        clock = clock
    )

    @Test
    fun listFiles_is_disabled_without_root() = runBlocking {
        val result = controller(null).listFiles()
        assertTrue(result is CotaResult.Disabled)
    }

    @Test
    fun listFiles_is_disabled_when_module_is_off() = runBlocking {
        val backend = FakeRootBackend()
        val result = controller(backend, enabled = false).listFiles()
        assertTrue(result is CotaResult.Disabled)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun listFiles_returns_name_size_hash_and_kind() = runBlocking {
        val backend = FakeRootBackend()
        val result = controller(backend).listFiles()

        assertTrue(result is CotaResult.Ok)
        val file = (result as CotaResult.Ok<List<CotaFile>>).value.single()
        assertEquals("cpuconfig", file.name)
        assertEquals(1024L, file.sizeBytes)
        assertEquals(hashA, file.sha256)
        assertEquals(CotaFileKind.CPU_CONFIG, file.kind)
        assertEquals("644", file.mode)
        assertEquals(1000, file.uid)
        assertEquals(1000, file.gid)
    }

    @Test
    fun backup_fails_without_selection() = runBlocking {
        val backend = FakeRootBackend()
        val result = controller(backend).backup(emptySet())
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.isEmpty())
    }

    @Test
    fun backup_copies_verifies_and_writes_manifest() = runBlocking {
        val backend = FakeRootBackend()
        backend.backupHashes = mapOf("cpuconfig" to hashA)

        val result = controller(backend).backup(setOf("cpuconfig"))

        assertTrue(result is CotaResult.Ok)
        val backup = (result as CotaResult.Ok<CotaBackup>).value
        assertEquals("20261121-183000", backup.id)
        assertEquals(hashA, backup.files.single().sha256)
        assertTrue(backend.commands.any { it.startsWith("mkdir -p ") })
        assertTrue(backend.commands.any { it.contains("cp -p ") })
        assertTrue(backend.commands.any { it.startsWith("sha256sum '/storage") })
        assertTrue(backend.commands.any { it.startsWith("printf '%s'") })
        assertTrue(backend.manifest!!.startsWith("#nemu-cota-backup-v1"))
        assertTrue(backend.manifest!!.contains("cpuconfig\t$hashA"))
    }

    @Test
    fun backup_rejects_a_mismatched_copy() = runBlocking {
        val backend = FakeRootBackend()
        backend.backupHashes = mapOf("cpuconfig" to hashB)

        val result = controller(backend).backup(setOf("cpuconfig"))

        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("printf '%s'") })
    }

    @Test
    fun delete_requires_risk_acknowledgement() = runBlocking {
        val backend = FakeRootBackend()
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = false
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
    }

    @Test
    fun delete_requires_a_backup_manifest() = runBlocking {
        val backend = FakeRootBackend()
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = true
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
    }

    @Test
    fun delete_refuses_when_the_file_changed_after_backup() = runBlocking {
        val backend = FakeRootBackend()
        backend.manifest = CotaParsers.renderManifest(
            listOf(CotaBackupFile("cpuconfig", hashB, 1024, "644", 1000, 1000))
        )
        backend.backupHashes = mapOf("cpuconfig" to hashB)
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = true
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
    }

    @Test
    fun delete_refuses_a_file_not_covered_by_the_backup() = runBlocking {
        val backend = FakeRootBackend()
        backend.manifest = CotaParsers.renderManifest(
            listOf(CotaBackupFile("other", hashA, 1024, "644", 1000, 1000))
        )
        backend.backupHashes = mapOf("other" to hashA)
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = true
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
        assertTrue(backend.files.containsKey("cpuconfig"))
    }

    @Test
    fun delete_refuses_a_corrupted_backup_before_removing() = runBlocking {
        val backend = FakeRootBackend()
        backend.manifest = CotaParsers.renderManifest(
            listOf(CotaBackupFile("cpuconfig", hashA, 1024, "644", 1000, 1000))
        )
        backend.backupHashes = mapOf("cpuconfig" to hashB)
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = true
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
        assertTrue(backend.files.containsKey("cpuconfig"))
    }

    @Test
    fun delete_removes_only_after_a_matching_backup_and_verifies_absence() =
        runBlocking {
            val backend = FakeRootBackend()
            backend.manifest = CotaParsers.renderManifest(
                listOf(CotaBackupFile("cpuconfig", hashA, 1024, "644", 1000, 1000))
            )
            backend.backupHashes = mapOf("cpuconfig" to hashA)
            val result = controller(backend).delete(
                names = setOf("cpuconfig"),
                backupId = "20261121-183000",
                riskAcknowledged = true
            )
            assertTrue(result is CotaResult.Ok)
            assertTrue(backend.files.isEmpty())
            assertTrue(
                backend.commands.any { it == "rm -f '/data/cota/para/cpuconfig'" }
            )
        }

    @Test
    fun restore_refuses_a_corrupted_backup_before_overwriting() = runBlocking {
        val backend = FakeRootBackend()
        backend.files["cpuconfig"] = FakeFile(1024, hashB, "644", 1000, 1000)
        backend.manifest = CotaParsers.renderManifest(
            listOf(CotaBackupFile("cpuconfig", hashA, 1024, "644", 1000, 1000))
        )
        backend.backupHashes = mapOf("cpuconfig" to hashB)

        val result = controller(backend).restore("20261121-183000")

        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("cp -p '/storage") })
        assertEquals(hashB, backend.files.getValue("cpuconfig").sha)
    }

    @Test
    fun restore_copies_and_verifies_the_manifest_hash() = runBlocking {
        val backend = FakeRootBackend()
        backend.files["cpuconfig"] = FakeFile(1024, hashB, "644", 1000, 1000)
        backend.manifest = CotaParsers.renderManifest(
            listOf(CotaBackupFile("cpuconfig", hashA, 1024, "644", 1000, 1000))
        )
        backend.backupHashes = mapOf("cpuconfig" to hashA)

        val result = controller(backend).restore("20261121-183000")

        assertTrue(result is CotaResult.Ok)
        assertEquals(hashA, backend.files.getValue("cpuconfig").sha)
        assertTrue(backend.commands.any { it.startsWith("cp -p '/storage") })
    }
}
