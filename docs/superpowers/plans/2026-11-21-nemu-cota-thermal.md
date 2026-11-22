# Nemu COTA/Thermal Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a root-only Nemu module that lists, backs up, deletes, and restores HONOR cloud-pushed performance/thermal parameters under `/data/cota/para`, with a hard backup-first invariant and explicit risk confirmation.

**Architecture:** All file access happens through a `RootPrivilegeBackend` shell (`su`); the app never uses `java.io.File`, `ContentResolver`, or external-storage permissions. Pure Kotlin builders/parsers construct every shell command and validate every file name; `CotaController` sequences list → hash → backup → verify → delete/restore and refuses to delete without a manifest-verified backup whose SHA-256 still matches the file on disk. A new Compose `CotaSection` is added to the existing `MainScreen`, off by default.

**Tech Stack:** Kotlin 2.4.20, AGP 9.1.0, Compose BOM 2025.12.01, Material3, kotlinx-coroutines 1.10.2, JUnit 4.13.2. No new dependencies.

**Spec:** `../../03-design.md` §7.3, `../../02-package-inventory.md` §5, `../../04-test-plan.md`, `../../../README.md`.

## Global Constraints

- **No Hail dependency or API.** No `com.aistra.hail.*` import, no Hail Intent, no Hail data.
- **Root-only.** `CotaController` accepts only `RootPrivilegeBackend?`; `ShizukuBackend` must not implement `RootPrivilegeBackend`. No Shizuku fallback for any COTA file operation.
- **Never touch** `com.hihonor.powergenie`, `com.hihonor.iaware`, vendor HALs, kernel thermal zones, or `/sys/class/thermal`. The module only operates on regular files directly inside `/data/cota/para`.
- **Backup-first invariant.** A delete is allowed only when (a) a valid backup manifest exists, (b) every selected file is covered by that manifest, and (c) the current file SHA-256 equals the manifest SHA-256. Otherwise the controller refuses and executes no `rm`.
- **No recursive or glob deletion.** Commands use `rm -f` with explicitly quoted absolute paths, one path per selected file. `rm -rf` must not appear in `app/src`.
- **Selected files only.** The UI starts with an empty selection; delete requires a risk checkbox in a confirmation dialog. No "delete all" action.
- **All device writes are user-executed.** The assistant only builds the APK and edits project files. Device tests in this plan are executed by the user.
- **Module default off.** `Settings.cotaEnabled` defaults to `false`; turning it off removes the action surface but does not undo deletions.
- **File-name policy.** Operable names match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. Unsafe names are skipped and logged, never interpolated into a shell command.
- UI strings are Chinese; code identifiers are English.
- Package name `com.nemu.hibernate`; `minSdk 30`, `targetSdk 36`, compile SDK 37.

## Decisions Locked by This Plan

- **Backup root:** `/storage/emulated/0/Nemu/cota-backup/<yyyyMMdd-HHmmss>/`. This is the canonical path for the spec's `/sdcard/Nemu/cota-backup/...` (avoids root-shell ambiguity around the `/sdcard` symlink). Each backup contains the copied files plus `manifest.tsv`.
- **Manifest:** version header `#nemu-cota-backup-v1`, then one TSV row per file: `name<TAB>sha256<TAB>size<TAB>mode<TAB>uid<TAB>gid`. The manifest travels with the backup, so restore does not depend on app data.
- **Backup timestamps to the second.** `mkdir` (not `mkdir -p`) creates the timestamp directory, so an existing backup is never overwritten; a collision is reported as an error.
- **Restore scope:** the UI restores every file in the chosen backup. The controller also accepts a subset for tests and future use.
- **Hash batching:** commands are chunked to 8 paths to stay under the backend's 15-second `su` timeout.

## File Structure

New files:

```text
app/src/main/java/com/nemu/hibernate/core/ShellQuote.kt
app/src/main/java/com/nemu/hibernate/privilege/RootPrivilegeBackend.kt
app/src/main/java/com/nemu/hibernate/cota/CotaModels.kt
app/src/main/java/com/nemu/hibernate/cota/CotaKnownFiles.kt
app/src/main/java/com/nemu/hibernate/cota/CotaPaths.kt
app/src/main/java/com/nemu/hibernate/cota/CotaCommandBuilder.kt
app/src/main/java/com/nemu/hibernate/cota/CotaParsers.kt
app/src/main/java/com/nemu/hibernate/cota/CotaController.kt
app/src/main/java/com/nemu/hibernate/ui/CotaSection.kt
app/src/test/java/com/nemu/hibernate/core/ShellQuoteTest.kt
app/src/test/java/com/nemu/hibernate/cota/CotaCommandBuilderTest.kt
app/src/test/java/com/nemu/hibernate/cota/CotaParsersTest.kt
app/src/test/java/com/nemu/hibernate/cota/CotaControllerTest.kt
```

Modified files:

```text
app/src/main/java/com/nemu/hibernate/core/CommandBuilder.kt      (delegate quoting to ShellQuote)
app/src/main/java/com/nemu/hibernate/privilege/RootBackend.kt   (implement RootPrivilegeBackend)
app/src/main/java/com/nemu/hibernate/privilege/BackendManager.kt (add getRoot())
app/src/main/java/com/nemu/hibernate/data/Settings.kt           (add cotaEnabled)
app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt           (add CotaSection)
app/src/main/java/com/nemu/hibernate/MainActivity.kt            (wire COTA state/callbacks)
README.md
docs/02-package-inventory.md
docs/03-design.md
docs/04-test-plan.md
```

---

### Task 1: Root-only privilege plumbing and shared shell quoting

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/core/ShellQuote.kt`
- Create: `app/src/main/java/com/nemu/hibernate/privilege/RootPrivilegeBackend.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/core/CommandBuilder.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/privilege/RootBackend.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/privilege/BackendManager.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/data/Settings.kt`
- Test: `app/src/test/java/com/nemu/hibernate/core/ShellQuoteTest.kt`

**Interfaces:**
- Consumes: existing `PrivilegeBackend`, `RootBackend`, `Settings`.
- Produces:
  - `object ShellQuote { fun quote(value: String): String }`.
  - `interface RootPrivilegeBackend : PrivilegeBackend` (marker).
  - `RootBackend : RootPrivilegeBackend`.
  - `BackendManager.getRoot(): RootPrivilegeBackend?` — checks real root availability and never returns Shizuku.
  - `Settings.cotaEnabled: Boolean` (default `false`).

- [ ] **Step 1: Preflight**

Run:

```powershell
cd D:\win_root\nemu
git status --short
git log --oneline -1
```

Expected: clean tree; HEAD is `fdc3ab1 feat: always-on safety unfreeze before shutdown and reboot`.

- [ ] **Step 2: Write the failing quoting test**

Create `app/src/test/java/com/nemu/hibernate/core/ShellQuoteTest.kt`:

```kotlin
package com.nemu.hibernate.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test
    fun plain_value_is_single_quoted() {
        assertEquals(
            "'/data/cota/para/cpuconfig'",
            ShellQuote.quote("/data/cota/para/cpuconfig")
        )
    }

    @Test
    fun apostrophe_is_escaped() {
        assertEquals("'a'\\''b'", ShellQuote.quote("a'b"))
    }
}
```

- [ ] **Step 3: Run the test and confirm it fails**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.core.ShellQuoteTest"
```

Expected: compilation fails because `ShellQuote` does not exist.

- [ ] **Step 4: Implement `ShellQuote` and use it from `CommandBuilder`**

Create `app/src/main/java/com/nemu/hibernate/core/ShellQuote.kt`:

```kotlin
package com.nemu.hibernate.core

object ShellQuote {
    fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
```

In `app/src/main/java/com/nemu/hibernate/core/CommandBuilder.kt`, replace the private helper body:

```kotlin
    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
```

with:

```kotlin
    private fun quote(value: String): String = ShellQuote.quote(value)
```

Do not change any command strings; `CommandBuilderTest` must keep passing unchanged.

- [ ] **Step 5: Add the root-only marker interface**

Create `app/src/main/java/com/nemu/hibernate/privilege/RootPrivilegeBackend.kt`:

```kotlin
package com.nemu.hibernate.privilege

/**
 * Marker for backends running as uid 0. COTA file operations accept only this
 * type. Shizuku runs as the shell uid (2000) and cannot read /data/cota, so it
 * deliberately does not implement this interface.
 */
interface RootPrivilegeBackend : PrivilegeBackend
```

- [ ] **Step 6: Make `RootBackend` implement the marker and expose `getRoot()`**

In `app/src/main/java/com/nemu/hibernate/privilege/RootBackend.kt`, change:

```kotlin
class RootBackend : PrivilegeBackend {
```

to:

```kotlin
class RootBackend : RootPrivilegeBackend {
```

In `app/src/main/java/com/nemu/hibernate/privilege/BackendManager.kt`, add after `get()`:

```kotlin
    /**
     * COTA/thermal is root-only. This never returns the Shizuku backend even
     * when Shizuku is available and selected as the general backend.
     */
    suspend fun getRoot(): RootPrivilegeBackend? =
        root.takeIf { it.isAvailable() }
```

- [ ] **Step 7: Add the persisted module switch**

In `app/src/main/java/com/nemu/hibernate/data/Settings.kt`, add after `dryRun`:

```kotlin
    var cotaEnabled: Boolean
        get() = prefs.getBoolean("cota_enabled", false)
        set(value) = prefs.edit().putBoolean("cota_enabled", value).apply()
```

- [ ] **Step 8: Run the tests and build**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `ShellQuoteTest` passes; all existing tests pass; `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/core/ShellQuote.kt `
  app/src/main/java/com/nemu/hibernate/core/CommandBuilder.kt `
  app/src/main/java/com/nemu/hibernate/privilege/RootPrivilegeBackend.kt `
  app/src/main/java/com/nemu/hibernate/privilege/RootBackend.kt `
  app/src/main/java/com/nemu/hibernate/privilege/BackendManager.kt `
  app/src/main/java/com/nemu/hibernate/data/Settings.kt `
  app/src/test/java/com/nemu/hibernate/core/ShellQuoteTest.kt
git commit -m "feat: add root-only COTA privilege plumbing"
```

---

### Task 2: COTA pure core (paths, commands, parsers)

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaModels.kt`
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaKnownFiles.kt`
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaPaths.kt`
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaCommandBuilder.kt`
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaParsers.kt`
- Test: `app/src/test/java/com/nemu/hibernate/cota/CotaCommandBuilderTest.kt`
- Test: `app/src/test/java/com/nemu/hibernate/cota/CotaParsersTest.kt`

**Interfaces:**
- Consumes: `ShellQuote`.
- Produces:
  - `CotaPaths.SOURCE_DIR = "/data/cota/para"`, `DEFAULT_BACKUP_ROOT = "/storage/emulated/0/Nemu/cota-backup"`, `MANIFEST_NAME = "manifest.tsv"`, `DIR_MISSING = "__NEMU_COTA_DIR_MISSING__"`.
  - `CotaPaths.isSafeName(name): Boolean`, `isSafeBackupId(id): Boolean`, `isSafeMode(mode): Boolean`, `sourcePath(name): String`, `backupDir(root, id): String`, `backupPath(dir, name): String`.
  - `enum class CotaFileKind`, `data class CotaFile`, `data class CotaBackupFile`, `data class CotaBackup`, `data class CotaBackupSummary`, `sealed class CotaResult<T>`.
  - `CotaKnownFiles.kindOf(name): CotaFileKind`.
  - `CotaCommandBuilder` functions with exact command strings.
  - `CotaParsers.isDirMissing`, `parseFileNames`, `parseStat`, `parseSha256`, `renderManifest`, `parseManifest`.

- [ ] **Step 1: Write the failing command-builder test**

Create `app/src/test/java/com/nemu/hibernate/cota/CotaCommandBuilderTest.kt`:

```kotlin
package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaCommandBuilderTest {
    @Test
    fun listFiles_uses_fixed_source_dir_and_missing_sentinel() {
        assertEquals(
            "if [ -d '/data/cota/para' ]; then find '/data/cota/para' " +
                "-maxdepth 1 -type f -print; else echo '__NEMU_COTA_DIR_MISSING__'; fi",
            CotaCommandBuilder.listFiles()
        )
    }

    @Test
    fun stat_uses_pipe_format_and_quoted_paths() {
        assertEquals(
            "stat -c '%n|%s|%a|%u|%g' '/data/cota/para/cpuconfig' " +
                "'/data/cota/para/pg_config'",
            CotaCommandBuilder.stat(
                listOf("/data/cota/para/cpuconfig", "/data/cota/para/pg_config")
            )
        )
    }

    @Test
    fun sha256_quotes_paths() {
        assertEquals(
            "sha256sum '/data/cota/para/thermal_config'",
            CotaCommandBuilder.sha256(listOf("/data/cota/para/thermal_config"))
        )
    }

    @Test
    fun backup_creates_parent_then_refuses_to_overwrite_existing_backup() {
        assertEquals(
            "mkdir -p '/storage/emulated/0/Nemu/cota-backup' && " +
                "mkdir '/storage/emulated/0/Nemu/cota-backup/20261121-183000' && " +
                "cp -p '/data/cota/para/cpuconfig' " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/'",
            CotaCommandBuilder.backup(
                "/storage/emulated/0/Nemu/cota-backup",
                "20261121-183000",
                listOf("/data/cota/para/cpuconfig")
            )
        )
    }

    @Test
    fun writeManifest_decodes_base64_into_the_backup_directory() {
        assertEquals(
            "printf '%s' 'aGVsbG8=' | base64 -d > " +
                "'/storage/emulated/0/Nemu/cota-backup/20261121-183000/manifest.tsv'",
            CotaCommandBuilder.writeManifest(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                "aGVsbG8="
            )
        )
    }

    @Test
    fun delete_uses_rm_f_on_selected_files_only() {
        assertEquals(
            "rm -f '/data/cota/para/cpuconfig' '/data/cota/para/pg_config'",
            CotaCommandBuilder.delete(
                listOf("/data/cota/para/cpuconfig", "/data/cota/para/pg_config")
            )
        )
    }

    @Test
    fun restore_copies_then_chowns_then_chmods() {
        assertEquals(
            "cp -p '/storage/emulated/0/Nemu/cota-backup/20261121-183000/cpuconfig' " +
                "'/data/cota/para/cpuconfig' && " +
                "chown '1000:1000' '/data/cota/para/cpuconfig' && " +
                "chmod '644' '/data/cota/para/cpuconfig'",
            CotaCommandBuilder.restore(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                listOf(
                    CotaBackupFile(
                        name = "cpuconfig",
                        sha256 = "a".repeat(64),
                        sizeBytes = 1024,
                        mode = "644",
                        uid = 1000,
                        gid = 1000
                    )
                )
            )
        )
    }

    @Test
    fun restore_rejects_unsafe_mode() {
        assertThrows(IllegalArgumentException::class.java) {
            CotaCommandBuilder.restore(
                "/storage/emulated/0/Nemu/cota-backup/20261121-183000",
                listOf(
                    CotaBackupFile(
                        name = "cpuconfig",
                        sha256 = "a".repeat(64),
                        sizeBytes = 1,
                        mode = "777; rm -rf /",
                        uid = 0,
                        gid = 0
                    )
                )
            )
        }
    }

    @Test
    fun unsafe_names_are_rejected_by_the_path_policy() {
        assertTrue(CotaPaths.isSafeName("thermal_config"))
        assertTrue(CotaPaths.isSafeName("cpuconfig.20261121"))
        assertFalse(CotaPaths.isSafeName("../thermal_config"))
        assertFalse(CotaPaths.isSafeName("thermal config"))
        assertFalse(CotaPaths.isSafeName("-rf"))
        assertFalse(CotaPaths.isSafeName("a/b"))
        assertFalse(CotaPaths.isSafeName(""))
    }
}
```

- [ ] **Step 2: Write the failing parser test**

Create `app/src/test/java/com/nemu/hibernate/cota/CotaParsersTest.kt`:

```kotlin
package com.nemu.hibernate.cota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotaParsersTest {
    private val hash = "a".repeat(64)

    @Test
    fun parseFileNames_keeps_safe_names_and_counts_skipped_entries() {
        val output = listOf(
            "/data/cota/para/cpuconfig",
            "/data/cota/para/pg_config",
            "/data/cota/para/thermal_config",
            "/data/cota/para/bad name",
            "/data/cota/para/../escape"
        ).joinToString("\n")

        val (names, skipped) = CotaParsers.parseFileNames(output)

        assertEquals(listOf("cpuconfig", "pg_config", "thermal_config"), names)
        assertEquals(2, skipped)
    }

    @Test
    fun parseStat_maps_pipe_separated_metadata() {
        val output = "/data/cota/para/cpuconfig|1024|644|1000|1000\n"

        val stats = CotaParsers.parseStat(output)

        assertEquals(1, stats.size)
        assertEquals("cpuconfig", stats[0].name)
        assertEquals(1024L, stats[0].sizeBytes)
        assertEquals("644", stats[0].mode)
        assertEquals(1000, stats[0].uid)
        assertEquals(1000, stats[0].gid)
    }

    @Test
    fun parseStat_drops_malformed_lines() {
        val output = "garbage\n/data/cota/para/cpuconfig|x|644|1000|1000\n"
        assertTrue(CotaParsers.parseStat(output).isEmpty())
    }

    @Test
    fun parseSha256_accepts_both_markers() {
        val output = "$hash  /data/cota/para/cpuconfig\n" +
            "${"b".repeat(64)} */data/cota/para/pg_config\n"

        val hashes = CotaParsers.parseSha256(output)

        assertEquals(hash, hashes["cpuconfig"])
        assertEquals("b".repeat(64), hashes["pg_config"])
    }

    @Test
    fun parseSha256_ignores_unsafe_paths() {
        val output = "$hash  /data/cota/para/bad name\n"
        assertTrue(CotaParsers.parseSha256(output).isEmpty())
    }

    @Test
    fun manifest_round_trips() {
        val files = listOf(
            CotaBackupFile("cpuconfig", hash, 1024, "644", 1000, 1000),
            CotaBackupFile("thermal_config", "c".repeat(64), 2048, "644", 0, 0)
        )

        val parsed = CotaParsers.parseManifest(CotaParsers.renderManifest(files))

        assertEquals(files.sortedBy { it.name }, parsed)
    }

    @Test
    fun manifest_rejects_missing_header_and_bad_rows() {
        assertNull(CotaParsers.parseManifest("cpuconfig\t$hash\t1\t644\t0\t0\n"))
        assertNull(
            CotaParsers.parseManifest(
                "#nemu-cota-backup-v1\ncpuconfig\tnot-a-hash\t1\t644\t0\t0\n"
            )
        )
    }
}
```

- [ ] **Step 3: Run the tests and confirm they fail**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.cota.*"
```

Expected: compilation fails because the `cota` package does not exist.

- [ ] **Step 4: Implement the COTA models**

Create `app/src/main/java/com/nemu/hibernate/cota/CotaModels.kt`:

```kotlin
package com.nemu.hibernate.cota

enum class CotaFileKind(val label: String, val description: String) {
    CPU_CONFIG("CPU 调度参数", "厂商下发的 CPU 频率/调度配置"),
    PG_CONFIG("PowerGenie 功耗策略", "厂商下发的功耗与充电策略参数"),
    THERMAL_CONFIG("温控阈值", "厂商下发的温度阈值与降频策略"),
    OTHER("其他云控参数", "未识别的 COTA 参数文件")
}

data class CotaFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
    val mode: String,
    val uid: Int,
    val gid: Int,
    val kind: CotaFileKind
)

data class CotaBackupFile(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val mode: String,
    val uid: Int,
    val gid: Int
)

data class CotaBackup(
    val id: String,
    val dir: String,
    val files: List<CotaBackupFile>
)

data class CotaBackupSummary(
    val id: String,
    val dir: String
)

sealed class CotaResult<out T> {
    data class Ok<T>(val value: T) : CotaResult<T>()
    data class Error(val message: String) : CotaResult<Nothing>()
    data object Disabled : CotaResult<Nothing>()
}
```

- [ ] **Step 5: Implement known-file labels and the path policy**

Create `app/src/main/java/com/nemu/hibernate/cota/CotaKnownFiles.kt`:

```kotlin
package com.nemu.hibernate.cota

object CotaKnownFiles {
    private val kinds = mapOf(
        "cpuconfig" to CotaFileKind.CPU_CONFIG,
        "pg_config" to CotaFileKind.PG_CONFIG,
        "thermal_config" to CotaFileKind.THERMAL_CONFIG
    )

    fun kindOf(name: String): CotaFileKind = kinds[name] ?: CotaFileKind.OTHER
}
```

Create `app/src/main/java/com/nemu/hibernate/cota/CotaPaths.kt`:

```kotlin
package com.nemu.hibernate.cota

object CotaPaths {
    const val SOURCE_DIR = "/data/cota/para"
    const val DEFAULT_BACKUP_ROOT = "/storage/emulated/0/Nemu/cota-backup"
    const val MANIFEST_NAME = "manifest.tsv"
    const val DIR_MISSING = "__NEMU_COTA_DIR_MISSING__"

    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val BACKUP_ID = Regex("^\\d{8}-\\d{6}$")
    private val MODE = Regex("^[0-7]{3,4}$")

    fun isSafeName(name: String): Boolean = SAFE_NAME.matches(name)

    fun isSafeBackupId(id: String): Boolean = BACKUP_ID.matches(id)

    fun isSafeMode(mode: String): Boolean = MODE.matches(mode)

    fun sourcePath(name: String): String {
        require(isSafeName(name)) { "unsafe COTA file name: $name" }
        return "$SOURCE_DIR/$name"
    }

    fun backupDir(root: String, id: String): String {
        require(isSafeBackupId(id)) { "unsafe backup id: $id" }
        return "$root/$id"
    }

    fun backupPath(dir: String, name: String): String {
        require(isSafeName(name)) { "unsafe COTA file name: $name" }
        return "$dir/$name"
    }
}
```

- [ ] **Step 6: Implement the command builder**

Create `app/src/main/java/com/nemu/hibernate/cota/CotaCommandBuilder.kt`:

```kotlin
package com.nemu.hibernate.cota

import com.nemu.hibernate.core.ShellQuote

object CotaCommandBuilder {
    fun listFiles(): String =
        "if [ -d ${q(CotaPaths.SOURCE_DIR)} ]; then " +
            "find ${q(CotaPaths.SOURCE_DIR)} -maxdepth 1 -type f -print; " +
            "else echo ${q(CotaPaths.DIR_MISSING)}; fi"

    fun stat(paths: List<String>): String {
        require(paths.isNotEmpty()) { "paths must not be empty" }
        return "stat -c '%n|%s|%a|%u|%g' " + paths.joinToString(" ") { q(it) }
    }

    fun sha256(paths: List<String>): String {
        require(paths.isNotEmpty()) { "paths must not be empty" }
        return "sha256sum " + paths.joinToString(" ") { q(it) }
    }

    fun backup(backupRoot: String, backupId: String, sources: List<String>): String {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        return "mkdir -p ${q(backupRoot)} && mkdir ${q(dir)} && cp -p " +
            sources.joinToString(" ") { q(it) } + " " + q("$dir/")
    }

    fun writeManifest(backupDir: String, manifestBase64: String): String =
        "printf '%s' ${q(manifestBase64)} | base64 -d > " +
            q("$backupDir/${CotaPaths.MANIFEST_NAME}")

    fun readManifest(backupDir: String): String =
        "cat ${q("$backupDir/${CotaPaths.MANIFEST_NAME}")}"

    fun listBackups(root: String): String =
        "if [ -d ${q(root)} ]; then " +
            "find ${q(root)} -mindepth 1 -maxdepth 1 -type d -name '20*' -print; " +
            "fi"

    fun delete(sources: List<String>): String {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        return "rm -f " + sources.joinToString(" ") { q(it) }
    }

    fun restore(backupDir: String, files: List<CotaBackupFile>): String {
        require(files.isNotEmpty()) { "files must not be empty" }
        return files.joinToString(" && ") { file ->
            require(CotaPaths.isSafeMode(file.mode)) { "unsafe mode: ${file.mode}" }
            require(file.uid >= 0 && file.gid >= 0) { "unsafe uid/gid" }
            val source = CotaPaths.backupPath(backupDir, file.name)
            val target = CotaPaths.sourcePath(file.name)
            "cp -p ${q(source)} ${q(target)}" +
                " && chown ${q("${file.uid}:${file.gid}")} ${q(target)}" +
                " && chmod ${q(file.mode)} ${q(target)}"
        }
    }

    fun dirExists(path: String): String =
        "[ -d ${q(path)} ] && echo yes || echo no"

    private fun q(value: String): String = ShellQuote.quote(value)
}
```

- [ ] **Step 7: Implement the parsers**

Create `app/src/main/java/com/nemu/hibernate/cota/CotaParsers.kt`:

```kotlin
package com.nemu.hibernate.cota

object CotaParsers {
    private const val MANIFEST_HEADER = "#nemu-cota-backup-v1"
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun isDirMissing(output: String): Boolean =
        output.trim() == CotaPaths.DIR_MISSING

    fun parseFileNames(output: String): Pair<List<String>, Int> {
        var skipped = 0
        val names = linkedSetOf<String>()
        val prefix = CotaPaths.SOURCE_DIR + "/"
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith(prefix)) {
                val name = line.removePrefix(prefix)
                if (CotaPaths.isSafeName(name)) names += name else skipped++
            } else {
                skipped++
            }
        }
        return names.sorted() to skipped
    }

    data class CotaStat(
        val name: String,
        val sizeBytes: Long,
        val mode: String,
        val uid: Int,
        val gid: Int
    )

    fun parseStat(output: String): List<CotaStat> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val path = parts[0]
            val name = path.substringAfterLast('/')
            val size = parts[1].toLongOrNull() ?: return@mapNotNull null
            val mode = parts[2]
            val uid = parts[3].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[4].toIntOrNull() ?: return@mapNotNull null
            if (!path.startsWith(CotaPaths.SOURCE_DIR + "/") ||
                !CotaPaths.isSafeName(name) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            CotaStat(name, size, mode, uid, gid)
        }.toList()

    fun parseSha256(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.length <= 66) return@forEach
            val hash = line.substring(0, 64)
            if (!SHA256.matches(hash)) return@forEach
            val marker = line.substring(64, 66)
            if (marker != "  " && marker != " *") return@forEach
            val path = line.substring(66)
            val name = path.substringAfterLast('/')
            if (!CotaPaths.isSafeName(name)) return@forEach
            result[name] = hash
        }
        return result
    }

    fun renderManifest(files: List<CotaBackupFile>): String = buildString {
        append(MANIFEST_HEADER).append('\n')
        files.sortedBy { it.name }.forEach { file ->
            append(file.name).append('\t')
            append(file.sha256).append('\t')
            append(file.sizeBytes).append('\t')
            append(file.mode).append('\t')
            append(file.uid).append('\t')
            append(file.gid).append('\n')
        }
    }

    fun parseManifest(text: String): List<CotaBackupFile>? {
        val lines = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.firstOrNull() != MANIFEST_HEADER) return null
        val files = lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 6) return@mapNotNull null
            val name = parts[0]
            val sha = parts[1]
            val size = parts[2].toLongOrNull() ?: return@mapNotNull null
            val mode = parts[3]
            val uid = parts[4].toIntOrNull() ?: return@mapNotNull null
            val gid = parts[5].toIntOrNull() ?: return@mapNotNull null
            if (!CotaPaths.isSafeName(name) ||
                !SHA256.matches(sha) ||
                !CotaPaths.isSafeMode(mode) ||
                uid < 0 || gid < 0
            ) return@mapNotNull null
            CotaBackupFile(name, sha, size, mode, uid, gid)
        }
        return files.takeIf { it.size == lines.size - 1 }
    }
}
```

- [ ] **Step 8: Run the tests and build**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.cota.*"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: `CotaCommandBuilderTest` and `CotaParsersTest` pass; all existing tests pass; `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/cota `
  app/src/test/java/com/nemu/hibernate/cota
git commit -m "feat: add COTA command builder and parsers"
```

---

### Task 3: Backup-first `CotaController`

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/cota/CotaController.kt`
- Test: `app/src/test/java/com/nemu/hibernate/cota/CotaControllerTest.kt`

**Interfaces:**
- Consumes: `RootPrivilegeBackend`, `CotaCommandBuilder`, `CotaParsers`, `CotaPaths`, `CotaResult`, `CotaFile`, `CotaBackup*`, `ActionLog`.
- Produces:
  - `class CotaController(backend: RootPrivilegeBackend?, enabled: Boolean, backupRoot: String = CotaPaths.DEFAULT_BACKUP_ROOT, clock: () -> String = { ... })`.
  - `suspend fun listFiles(): CotaResult<List<CotaFile>>`.
  - `suspend fun backup(names: Set<String>): CotaResult<CotaBackup>`.
  - `suspend fun listBackups(): CotaResult<List<CotaBackupSummary>>`.
  - `suspend fun loadBackup(summary: CotaBackupSummary): CotaResult<CotaBackup>`.
  - `suspend fun delete(names: Set<String>, backupId: String, riskAcknowledged: Boolean): CotaResult<Unit>`.
  - `suspend fun restore(backupId: String, names: Set<String> = emptySet()): CotaResult<Unit>`.

- [ ] **Step 1: Write the failing controller test**

Create `app/src/test/java/com/nemu/hibernate/cota/CotaControllerTest.kt`:

```kotlin
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
        val result = controller(backend).delete(
            names = setOf("cpuconfig"),
            backupId = "20261121-183000",
            riskAcknowledged = true
        )
        assertTrue(result is CotaResult.Error)
        assertTrue(backend.commands.none { it.startsWith("rm -f ") })
    }

    @Test
    fun delete_removes_only_after_a_matching_backup_and_verifies_absence() =
        runBlocking {
            val backend = FakeRootBackend()
            backend.manifest = CotaParsers.renderManifest(
                listOf(CotaBackupFile("cpuconfig", hashA, 1024, "644", 1000, 1000))
            )
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
```

- [ ] **Step 2: Run the test and confirm it fails**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.cota.CotaControllerTest"
```

Expected: compilation fails because `CotaController` does not exist.

- [ ] **Step 3: Implement `CotaController`**

Create `app/src/main/java/com/nemu/hibernate/cota/CotaController.kt`:

```kotlin
package com.nemu.hibernate.cota

import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.privilege.RootPrivilegeBackend
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

class CotaController(
    private val backend: RootPrivilegeBackend?,
    private val enabled: Boolean,
    private val backupRoot: String = CotaPaths.DEFAULT_BACKUP_ROOT,
    private val clock: () -> String = { LocalDateTime.now().format(BACKUP_ID_FORMAT) }
) {
    private fun activeBackend(): RootPrivilegeBackend? =
        if (enabled) backend else null

    suspend fun listFiles(): CotaResult<List<CotaFile>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val listing = b.exec(CotaCommandBuilder.listFiles())
        if (!listing.ok) {
            return CotaResult.Error(
                "读取目录失败（${listing.exitCode}）：${listing.output.take(300)}"
            )
        }
        if (CotaParsers.isDirMissing(listing.output)) {
            return CotaResult.Error("目录不存在：${CotaPaths.SOURCE_DIR}")
        }
        val (names, skipped) = CotaParsers.parseFileNames(listing.output)
        if (skipped > 0) {
            ActionLog.warn("COTA：跳过 $skipped 个无法安全处理的条目")
        }
        if (names.isEmpty()) return CotaResult.Ok(emptyList())

        val paths = names.map { CotaPaths.sourcePath(it) }
        val stats = when (val result = statChunks(b, paths)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        if (stats.keys != names.toSet()) {
            return CotaResult.Error("文件列表在读取过程中发生变化，请刷新")
        }

        val hashes = when (val result = hashChunks(b, paths)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        if (hashes.keys != names.toSet()) {
            return CotaResult.Error("SHA-256 结果不完整，请刷新")
        }

        return CotaResult.Ok(
            names.map { name ->
                val stat = stats.getValue(name)
                CotaFile(
                    name = name,
                    sizeBytes = stat.sizeBytes,
                    sha256 = hashes.getValue(name),
                    mode = stat.mode,
                    uid = stat.uid,
                    gid = stat.gid,
                    kind = CotaKnownFiles.kindOf(name)
                )
            }
        )
    }

    suspend fun backup(names: Set<String>): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (names.isEmpty()) return CotaResult.Error("未选择文件")
        if (names.any { !CotaPaths.isSafeName(it) }) {
            return CotaResult.Error("包含无法安全处理的文件名")
        }
        val selected = names.sorted()

        val current = when (val result = listFiles()) {
            is CotaResult.Ok -> result.value.associateBy { it.name }
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val missing = selected.filter { it !in current }
        if (missing.isNotEmpty()) {
            return CotaResult.Error("文件不存在或已变化：${missing.joinToString()}")
        }

        val backupId = clock()
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份时间戳无效：$backupId")
        }
        val dir = CotaPaths.backupDir(backupRoot, backupId)
        val sources = selected.map { CotaPaths.sourcePath(it) }
        val copy = b.exec(CotaCommandBuilder.backup(backupRoot, backupId, sources))
        if (!copy.ok) {
            return CotaResult.Error(
                "备份复制失败（${copy.exitCode}）：${copy.output.take(300)}"
            )
        }

        val copiedHashes = when (
            val result = hashChunks(
                b,
                selected.map { CotaPaths.backupPath(dir, it) }
            )
        ) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val manifestFiles = selected.map { name ->
            val source = current.getValue(name)
            val copied = copiedHashes[name]
                ?: return CotaResult.Error("备份缺少文件：$name")
            if (copied != source.sha256) {
                return CotaResult.Error("备份校验失败：$name")
            }
            CotaBackupFile(
                name = name,
                sha256 = source.sha256,
                sizeBytes = source.sizeBytes,
                mode = source.mode,
                uid = source.uid,
                gid = source.gid
            )
        }

        val manifest = CotaParsers.renderManifest(manifestFiles)
        val encoded = Base64.getEncoder()
            .encodeToString(manifest.toByteArray(Charsets.UTF_8))
        val wrote = b.exec(CotaCommandBuilder.writeManifest(dir, encoded))
        if (!wrote.ok) {
            return CotaResult.Error(
                "写入备份清单失败（${wrote.exitCode}）：${wrote.output.take(300)}"
            )
        }
        val readBack = b.exec(CotaCommandBuilder.readManifest(dir))
        if (!readBack.ok) {
            return CotaResult.Error("备份清单回读失败（${readBack.exitCode}）")
        }
        val parsed = CotaParsers.parseManifest(readBack.output)
            ?: return CotaResult.Error("备份清单格式无效")
        if (parsed.sortedBy { it.name } != manifestFiles.sortedBy { it.name }) {
            return CotaResult.Error("备份清单校验失败")
        }

        ActionLog.info("COTA 备份完成：$dir（${manifestFiles.size} 个文件）")
        return CotaResult.Ok(CotaBackup(backupId, dir, manifestFiles))
    }

    suspend fun listBackups(): CotaResult<List<CotaBackupSummary>> {
        val b = activeBackend() ?: return CotaResult.Disabled
        val result = b.exec(CotaCommandBuilder.listBackups(backupRoot))
        if (!result.ok) {
            return CotaResult.Error(
                "读取备份目录失败（${result.exitCode}）：${result.output.take(300)}"
            )
        }
        val ids = result.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .filter { CotaPaths.isSafeBackupId(it) }
            .distinct()
            .sortedDescending()
            .toList()
        return CotaResult.Ok(
            ids.map { CotaBackupSummary(it, CotaPaths.backupDir(backupRoot, it)) }
        )
    }

    suspend fun loadBackup(summary: CotaBackupSummary): CotaResult<CotaBackup> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(summary.id)) {
            return CotaResult.Error("备份编号无效：${summary.id}")
        }
        val dir = CotaPaths.backupDir(backupRoot, summary.id)
        val result = b.exec(CotaCommandBuilder.readManifest(dir))
        if (!result.ok) {
            return CotaResult.Error("读取备份清单失败（${result.exitCode}）")
        }
        val files = CotaParsers.parseManifest(result.output)
            ?: return CotaResult.Error("备份清单格式无效：${summary.id}")
        if (files.isEmpty()) return CotaResult.Error("备份为空：${summary.id}")
        return CotaResult.Ok(CotaBackup(summary.id, dir, files))
    }

    suspend fun delete(
        names: Set<String>,
        backupId: String,
        riskAcknowledged: Boolean
    ): CotaResult<Unit> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!riskAcknowledged) return CotaResult.Error("未确认风险，已取消删除")
        if (names.isEmpty()) return CotaResult.Error("未选择文件")
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份编号无效")
        }
        if (names.any { !CotaPaths.isSafeName(it) }) {
            return CotaResult.Error("包含无法安全处理的文件名")
        }

        val summary = CotaBackupSummary(
            backupId,
            CotaPaths.backupDir(backupRoot, backupId)
        )
        val backup = when (val result = loadBackup(summary)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error ->
                return CotaResult.Error("没有可用备份，已取消删除：${result.message}")
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val backedUp = backup.files.associateBy { it.name }
        val notBackedUp = names.filter { it !in backedUp }
        if (notBackedUp.isNotEmpty()) {
            return CotaResult.Error("以下文件不在备份中：${notBackedUp.joinToString()}")
        }

        val current = when (val result = listFiles()) {
            is CotaResult.Ok -> result.value.associateBy { it.name }
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (name in names) {
            val now = current[name] ?: return CotaResult.Error("文件已不存在：$name")
            if (now.sha256 != backedUp.getValue(name).sha256) {
                return CotaResult.Error(
                    "备份后文件已变化，已取消删除：$name（请重新备份）"
                )
            }
        }

        val command = CotaCommandBuilder.delete(
            names.sorted().map { CotaPaths.sourcePath(it) }
        )
        val result = b.exec(command)
        if (!result.ok) {
            return CotaResult.Error(
                "删除失败（${result.exitCode}）：${result.output.take(300)}"
            )
        }
        ActionLog.warn("COTA 已删除：${names.sorted().joinToString()}（备份 $backupId）")

        val remaining = when (val after = listFiles()) {
            is CotaResult.Ok -> after.value.map { it.name }.toSet()
            is CotaResult.Error ->
                return CotaResult.Error("删除命令已执行，但校验失败：${after.message}")
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val stillThere = names.filter { it in remaining }
        if (stillThere.isNotEmpty()) {
            return CotaResult.Error("删除后文件仍存在：${stillThere.joinToString()}")
        }
        return CotaResult.Ok(Unit)
    }

    suspend fun restore(
        backupId: String,
        names: Set<String> = emptySet()
    ): CotaResult<Unit> {
        val b = activeBackend() ?: return CotaResult.Disabled
        if (!CotaPaths.isSafeBackupId(backupId)) {
            return CotaResult.Error("备份编号无效")
        }
        val summary = CotaBackupSummary(
            backupId,
            CotaPaths.backupDir(backupRoot, backupId)
        )
        val backup = when (val result = loadBackup(summary)) {
            is CotaResult.Ok -> result.value
            is CotaResult.Error -> return result
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        val backedUp = backup.files.associateBy { it.name }
        val selected =
            if (names.isEmpty()) backup.files.map { it.name }.toSet() else names
        val missing = selected.filter { it !in backedUp }
        if (missing.isNotEmpty()) {
            return CotaResult.Error("备份中没有：${missing.joinToString()}")
        }

        val dirCheck = b.exec(CotaCommandBuilder.dirExists(CotaPaths.SOURCE_DIR))
        if (!dirCheck.ok || dirCheck.output.trim() != "yes") {
            return CotaResult.Error(
                "目标目录不存在，已取消恢复：${CotaPaths.SOURCE_DIR}"
            )
        }

        val files = selected.sorted().map { backedUp.getValue(it) }
        val result = b.exec(CotaCommandBuilder.restore(backup.dir, files))
        if (!result.ok) {
            return CotaResult.Error(
                "恢复失败（${result.exitCode}）：${result.output.take(300)}"
            )
        }

        val hashes = when (
            val checked = hashChunks(
                b,
                files.map { CotaPaths.sourcePath(it.name) }
            )
        ) {
            is CotaResult.Ok -> checked.value
            is CotaResult.Error -> return checked
            CotaResult.Disabled -> return CotaResult.Disabled
        }
        for (file in files) {
            val actual = hashes[file.name]
                ?: return CotaResult.Error("恢复后缺少文件：${file.name}")
            if (actual != file.sha256) {
                return CotaResult.Error("恢复校验失败：${file.name}")
            }
        }

        ActionLog.info("COTA 恢复完成：${files.joinToString { it.name }}（备份 $backupId）")
        return CotaResult.Ok(Unit)
    }

    private suspend fun statChunks(
        backend: RootPrivilegeBackend,
        paths: List<String>
    ): CotaResult<Map<String, CotaParsers.CotaStat>> {
        if (paths.isEmpty()) return CotaResult.Ok(emptyMap())
        val stats = linkedMapOf<String, CotaParsers.CotaStat>()
        for (chunk in paths.chunked(COMMAND_CHUNK)) {
            val result = backend.exec(CotaCommandBuilder.stat(chunk))
            if (!result.ok) {
                return CotaResult.Error(
                    "读取文件属性失败（${result.exitCode}）：${result.output.take(300)}"
                )
            }
            CotaParsers.parseStat(result.output).forEach { stats[it.name] = it }
        }
        return CotaResult.Ok(stats)
    }

    private suspend fun hashChunks(
        backend: RootPrivilegeBackend,
        paths: List<String>
    ): CotaResult<Map<String, String>> {
        if (paths.isEmpty()) return CotaResult.Ok(emptyMap())
        val hashes = linkedMapOf<String, String>()
        for (chunk in paths.chunked(COMMAND_CHUNK)) {
            val result = backend.exec(CotaCommandBuilder.sha256(chunk))
            if (!result.ok) {
                return CotaResult.Error(
                    "SHA-256 失败（${result.exitCode}）：${result.output.take(300)}"
                )
            }
            hashes.putAll(CotaParsers.parseSha256(result.output))
        }
        return CotaResult.Ok(hashes)
    }

    companion object {
        private const val COMMAND_CHUNK = 8
        private val BACKUP_ID_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}
```

- [ ] **Step 4: Run the tests and build**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --tests "com.nemu.hibernate.cota.CotaControllerTest"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: all `CotaControllerTest` cases pass; all existing tests pass; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/cota/CotaController.kt `
  app/src/test/java/com/nemu/hibernate/cota/CotaControllerTest.kt
git commit -m "feat: add COTA backup-first controller"
```

---

### Task 4: COTA UI section and activity wiring

**Files:**
- Create: `app/src/main/java/com/nemu/hibernate/ui/CotaSection.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/nemu/hibernate/MainActivity.kt`

**Interfaces:**
- Consumes: `CotaController`, `CotaFile`, `CotaBackupSummary`, `CotaResult`, `BackendManager.getRoot`, `Settings.cotaEnabled`.
- Produces:
  - `data class CotaUiState` and `data class CotaCallbacks` in `com.nemu.hibernate.ui`.
  - `@Composable fun CotaSection(state: CotaUiState, callbacks: CotaCallbacks)`.
  - `MainScreen(...)` gains `cotaState` and `cotaCallbacks` parameters.
  - `MainActivity` creates a `CotaController` per action with `manager.getRoot()`.

- [ ] **Step 1: Create `CotaSection.kt`**

Create `app/src/main/java/com/nemu/hibernate/ui/CotaSection.kt`:

```kotlin
package com.nemu.hibernate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaFile

data class CotaUiState(
    val moduleEnabled: Boolean = false,
    val rootAvailable: Boolean = false,
    val busy: Boolean = false,
    val files: List<CotaFile> = emptyList(),
    val selected: Set<String> = emptySet(),
    val backups: List<CotaBackupSummary> = emptyList(),
    val lastBackupId: String? = null,
    val message: String? = null
)

data class CotaCallbacks(
    val onEnabledChange: (Boolean) -> Unit,
    val onRefresh: () -> Unit,
    val onToggleSelected: (String, Boolean) -> Unit,
    val onBackup: () -> Unit,
    val onDeleteConfirmed: () -> Unit,
    val onRestore: (String) -> Unit
)

@Composable
fun CotaSection(
    state: CotaUiState,
    callbacks: CotaCallbacks
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var riskChecked by remember { mutableStateOf(false) }
    var restoreId by remember { mutableStateOf<String?>(null) }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "温控 / COTA 参数（仅 Root）",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "读取 /data/cota/para 下的厂商云控参数。此模块只使用 Root；" +
                    "Shizuku 是 shell 权限，无法访问该目录，也不会被使用。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "风险：删除温控/功耗参数可能改变降频与充电策略，导致发热、" +
                    "充电变慢或系统不稳定；不保证提升性能；系统可能重新下发参数；" +
                    "错误文件或没有备份可能导致无法恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.moduleEnabled,
                    onCheckedChange = callbacks.onEnabledChange,
                    enabled = !state.busy
                )
                Spacer(Modifier.width(8.dp))
                Text("启用温控/COTA 模块（默认关闭）")
            }
            if (!state.rootAvailable) {
                Text(
                    "当前没有 Root，模块不可用。请先在 KSU 管理器中授权 Nemu。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.message != null) {
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = callbacks.onRefresh,
                    enabled = state.moduleEnabled && state.rootAvailable && !state.busy
                ) { Text("刷新文件/备份") }
                Button(
                    onClick = callbacks.onBackup,
                    enabled = state.moduleEnabled && state.rootAvailable &&
                        !state.busy && state.selected.isNotEmpty()
                ) { Text("备份选中") }
                OutlinedButton(
                    onClick = {
                        riskChecked = false
                        showDeleteDialog = true
                    },
                    enabled = state.moduleEnabled && state.rootAvailable &&
                        !state.busy && state.selected.isNotEmpty() &&
                        state.lastBackupId != null
                ) { Text("删除选中（危险）") }
            }
            if (state.lastBackupId != null) {
                Text(
                    "删除将使用备份：${state.lastBackupId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.files.isEmpty()) {
                Text("尚未读取文件。", style = MaterialTheme.typography.bodySmall)
            }
            state.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = file.name in state.selected,
                        onCheckedChange = {
                            callbacks.onToggleSelected(file.name, it)
                        },
                        enabled = state.moduleEnabled && state.rootAvailable && !state.busy
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${file.name}（${file.kind.label}）",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            file.kind.description,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "大小：${formatSize(file.sizeBytes)}  " +
                                "mode=${file.mode} ${file.uid}:${file.gid}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            file.sha256,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (state.backups.isNotEmpty()) {
                Text("备份", style = MaterialTheme.typography.titleSmall)
                state.backups.forEach { backup ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(backup.id)
                            Text(
                                backup.dir,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            onClick = { restoreId = backup.id },
                            enabled = state.moduleEnabled && state.rootAvailable &&
                                !state.busy
                        ) { Text("恢复") }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("确认删除 COTA 参数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将删除以下文件：")
                    state.selected.sorted().forEach { name ->
                        Text(name, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "已备份到：${state.lastBackupId}。删除后请观察发热、充电和" +
                            "系统稳定性；系统可能重新下发参数。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = riskChecked,
                            onCheckedChange = { riskChecked = it }
                        )
                        Text(
                            "我已了解风险，并确认备份可用",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        callbacks.onDeleteConfirmed()
                    },
                    enabled = riskChecked
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (restoreId != null) {
        AlertDialog(
            onDismissRequest = { restoreId = null },
            title = { Text("确认恢复") },
            text = {
                Text(
                    "将用备份 ${restoreId} 覆盖 /data/cota/para 中的同名文件；" +
                        "当前内容会被替换。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = restoreId
                        restoreId = null
                        if (id != null) callbacks.onRestore(id)
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreId = null }) { Text("取消") }
            }
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.2f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.2f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
```

- [ ] **Step 2: Add the section to `MainScreen`**

In `app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt`, add these two parameters at the end of the signature:

```kotlin
    onRestore: () -> Unit,
    cotaState: CotaUiState,
    cotaCallbacks: CotaCallbacks
) {
```

Then insert this item immediately after the first `item { Card { ... } }` block (the card that ends before `items(targets)`):

```kotlin
            item {
                CotaSection(state = cotaState, callbacks = cotaCallbacks)
            }
```

Do not remove the existing target and log items.

- [ ] **Step 3: Wire `MainActivity`**

Replace `app/src/main/java/com/nemu/hibernate/MainActivity.kt` with the following complete file:

```kotlin
package com.nemu.hibernate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nemu.hibernate.core.Targets
import com.nemu.hibernate.cota.CotaBackupSummary
import com.nemu.hibernate.cota.CotaController
import com.nemu.hibernate.cota.CotaFile
import com.nemu.hibernate.cota.CotaResult
import com.nemu.hibernate.data.ActionLog
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.policy.FreezeController
import com.nemu.hibernate.policy.SafetyService
import com.nemu.hibernate.privilege.BackendManager
import com.nemu.hibernate.ui.CotaCallbacks
import com.nemu.hibernate.ui.CotaUiState
import com.nemu.hibernate.ui.NemuTheme
import com.nemu.hibernate.ui.MainScreen
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var manager: BackendManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        manager = BackendManager(settings)

        SafetyService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ActionLog.info("Shizuku 权限已授予")
            } else {
                ActionLog.warn("Shizuku 权限被拒绝")
            }
        }

        setContent {
            NemuTheme {
                val scope = rememberCoroutineScope()
                var backendStatus by remember { mutableStateOf("检测中…") }
                var busy by remember { mutableStateOf(false) }
                var dryRun by remember { mutableStateOf(settings.dryRun) }
                val lastShutdownResult = remember {
                    settings.lastShutdownResult ?: "尚未执行"
                }
                val lastBootResult = remember {
                    settings.lastBootResult ?: "尚未执行"
                }
                val enabled = remember { mutableStateMapOf<String, Boolean>() }
                val logs by ActionLog.entries.collectAsState()

                var cotaEnabled by remember { mutableStateOf(settings.cotaEnabled) }
                var cotaRootAvailable by remember { mutableStateOf(false) }
                var cotaBusy by remember { mutableStateOf(false) }
                var cotaFiles by remember {
                    mutableStateOf<List<CotaFile>>(emptyList())
                }
                var cotaSelected by remember {
                    mutableStateOf<Set<String>>(emptySet())
                }
                var cotaBackups by remember {
                    mutableStateOf<List<CotaBackupSummary>>(emptyList())
                }
                var cotaLastBackupId by remember { mutableStateOf<String?>(null) }
                var cotaMessage by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    Targets.all.forEach {
                        enabled[it.packageName] = settings.isEnabled(it.packageName)
                    }
                    val root = manager.getRoot()
                    cotaRootAvailable = root != null
                    backendStatus = manager.status()
                }

                fun refreshCota() {
                    scope.launch {
                        cotaBusy = true
                        val root = manager.getRoot()
                        cotaRootAvailable = root != null
                        val controller = CotaController(root, cotaEnabled)
                        when (val result = controller.listFiles()) {
                            is CotaResult.Ok -> {
                                cotaFiles = result.value
                                cotaSelected = emptySet()
                                cotaMessage = "读取到 ${result.value.size} 个文件"
                            }
                            is CotaResult.Error -> cotaMessage = result.message
                            CotaResult.Disabled ->
                                cotaMessage = "模块未启用或没有 Root"
                        }
                        when (val backups = controller.listBackups()) {
                            is CotaResult.Ok -> {
                                cotaBackups = backups.value
                                if (cotaLastBackupId == null) {
                                    cotaLastBackupId =
                                        backups.value.firstOrNull()?.id
                                }
                            }
                            else -> Unit
                        }
                        cotaBusy = false
                    }
                }

                MainScreen(
                    backendStatus = backendStatus,
                    lastShutdownResult = lastShutdownResult,
                    lastBootResult = lastBootResult,
                    targets = Targets.all,
                    enabled = enabled,
                    dryRun = dryRun,
                    logs = logs,
                    busy = busy,
                    onToggle = { packageName, value ->
                        settings.setEnabled(packageName, value)
                        enabled[packageName] = value
                    },
                    onDryRunChange = {
                        settings.dryRun = it
                        dryRun = it
                    },
                    onRequestShizuku = {
                        runCatching {
                            if (!Shizuku.pingBinder()) {
                                ActionLog.warn(
                                    "Shizuku 未运行，请先用 Shizuku Store/无线调试启动"
                                )
                            } else if (
                                Shizuku.checkSelfPermission() !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                Shizuku.requestPermission(0)
                            } else {
                                ActionLog.info("Shizuku 权限已授予")
                            }
                        }
                    },
                    onApply = {
                        scope.launch {
                            busy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；请先授权 Root 或启动 Shizuku")
                            } else {
                                FreezeController(backend, settings).apply(Targets.all)
                                backendStatus = manager.status()
                            }
                            busy = false
                        }
                    },
                    onRestore = {
                        scope.launch {
                            busy = true
                            val backend = manager.get()
                            if (backend == null) {
                                ActionLog.warn("无可用权限；无法恢复")
                            } else {
                                FreezeController(backend, settings).restore(Targets.all)
                                backendStatus = manager.status()
                            }
                            busy = false
                        }
                    },
                    cotaState = CotaUiState(
                        moduleEnabled = cotaEnabled,
                        rootAvailable = cotaRootAvailable,
                        busy = cotaBusy,
                        files = cotaFiles,
                        selected = cotaSelected,
                        backups = cotaBackups,
                        lastBackupId = cotaLastBackupId,
                        message = cotaMessage
                    ),
                    cotaCallbacks = CotaCallbacks(
                        onEnabledChange = { value ->
                            settings.cotaEnabled = value
                            cotaEnabled = value
                            if (!value) {
                                cotaFiles = emptyList()
                                cotaSelected = emptySet()
                                cotaMessage = "模块已关闭；已删除的文件不会自动恢复"
                            }
                        },
                        onRefresh = { refreshCota() },
                        onToggleSelected = { name, checked ->
                            cotaSelected = if (checked) {
                                cotaSelected + name
                            } else {
                                cotaSelected - name
                            }
                        },
                        onBackup = {
                            scope.launch {
                                cotaBusy = true
                                val root = manager.getRoot()
                                cotaRootAvailable = root != null
                                val controller = CotaController(root, cotaEnabled)
                                when (val result = controller.backup(cotaSelected)) {
                                    is CotaResult.Ok -> {
                                        cotaLastBackupId = result.value.id
                                        cotaMessage =
                                            "备份完成：${result.value.dir}"
                                        when (val backups = controller.listBackups()) {
                                            is CotaResult.Ok ->
                                                cotaBackups = backups.value
                                            else -> Unit
                                        }
                                    }
                                    is CotaResult.Error ->
                                        cotaMessage = result.message
                                    CotaResult.Disabled ->
                                        cotaMessage = "模块未启用或没有 Root"
                                }
                                cotaBusy = false
                            }
                        },
                        onDeleteConfirmed = {
                            scope.launch {
                                val backupId = cotaLastBackupId
                                if (backupId == null) {
                                    cotaMessage = "请先备份，再删除"
                                } else {
                                    cotaBusy = true
                                    val root = manager.getRoot()
                                    cotaRootAvailable = root != null
                                    val controller =
                                        CotaController(root, cotaEnabled)
                                    when (
                                        val result = controller.delete(
                                            names = cotaSelected,
                                            backupId = backupId,
                                            riskAcknowledged = true
                                        )
                                    ) {
                                        is CotaResult.Ok -> {
                                            cotaMessage =
                                                "删除完成；备份 $backupId 可用于恢复"
                                            cotaSelected = emptySet()
                                            when (
                                                val listed =
                                                    controller.listFiles()
                                            ) {
                                                is CotaResult.Ok ->
                                                    cotaFiles = listed.value
                                                else -> Unit
                                            }
                                        }
                                        is CotaResult.Error ->
                                            cotaMessage = result.message
                                        CotaResult.Disabled ->
                                            cotaMessage = "模块未启用或没有 Root"
                                    }
                                    cotaBusy = false
                                }
                            }
                        },
                        onRestore = { backupId ->
                            scope.launch {
                                cotaBusy = true
                                val root = manager.getRoot()
                                cotaRootAvailable = root != null
                                val controller = CotaController(root, cotaEnabled)
                                when (
                                    val result = controller.restore(backupId)
                                ) {
                                    is CotaResult.Ok -> {
                                        cotaMessage =
                                            "已从备份 $backupId 恢复"
                                        when (
                                            val listed = controller.listFiles()
                                        ) {
                                            is CotaResult.Ok ->
                                                cotaFiles = listed.value
                                            else -> Unit
                                        }
                                    }
                                    is CotaResult.Error ->
                                        cotaMessage = result.message
                                    CotaResult.Disabled ->
                                        cotaMessage = "模块未启用或没有 Root"
                                }
                                cotaBusy = false
                            }
                        }
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SafetyService.start(this)
    }
}
```

- [ ] **Step 4: Build and run all tests**

Run:

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: all tests pass; `BUILD SUCCESSFUL`; APK at `app\build\outputs\apk\debug\app-debug.apk`.

- [ ] **Step 5: Code-review the guard surface (no device writes)**

Run these read-only checks:

```powershell
cd D:\win_root\nemu
Select-String -Path app\src\main\java\com\nemu\hibernate\cota\*.kt -Pattern 'Shizuku|java\.io\.File|contentResolver|rm -rf|powergenie|iaware|thermal_zone'
Select-String -Path app\src\main\java\com\nemu\hibernate\privilege\*.kt -Pattern 'RootPrivilegeBackend'
```

Expected: the first command returns no matches (the `cota` package contains no Shizuku, direct file I/O, `rm -rf`, or thermal-service references). The second shows the marker in `RootPrivilegeBackend.kt`, `RootBackend.kt`, and `BackendManager.kt`.

- [ ] **Step 6: Commit**

```powershell
cd D:\win_root\nemu
git add app/src/main/java/com/nemu/hibernate/ui/CotaSection.kt `
  app/src/main/java/com/nemu/hibernate/ui/MainScreen.kt `
  app/src/main/java/com/nemu/hibernate/MainActivity.kt
git commit -m "feat: add COTA thermal UI"
```

---

### Task 5: Documentation, acceptance build, and device-test handoff

**Files:**
- Modify: `README.md`
- Modify: `docs/03-design.md` §7.3 and §7.4 status table
- Modify: `docs/04-test-plan.md` (add COTA1–COTA6, acceptance items, §7.4)
- Modify: `docs/02-package-inventory.md` §5
- Add: `docs/superpowers/plans/2026-11-21-nemu-cota-thermal.md` (this plan)

**Interfaces:**
- Consumes: the built APK and the test results from Tasks 1–4.
- Produces: documented behavior, risks, exact device-test commands, and an acceptance APK hash. Device results are marked `未测` until the user runs them.

- [ ] **Step 1: Update `README.md`**

Change the status bullet:

```markdown
- 2026-11-21：核心 + 安全解冻已实机验收（Shizuku 后端、绿色包/OUC）。温控/COTA 模块已实现，待实机验收。
```

Add this section after “安全解冻（始终开启）” and before “文档”:

```markdown
## 温控 / COTA（root-only，默认关闭）

- 列出 `/data/cota/para` 下的普通文件（不递归），显示名称、大小、mode/uid/gid、SHA-256。
- 已知参数：`cpuconfig`（CPU 调度）、`pg_config`（PowerGenie 功耗策略）、`thermal_config`（温控阈值）；其他文件显示为“其他云控参数”。
- 备份到 `/storage/emulated/0/Nemu/cota-backup/<yyyyMMdd-HHmmss>/`（等价于 `/sdcard/Nemu/cota-backup/...`），备份目录内包含 `manifest.tsv`（文件清单 + SHA-256 + mode/uid/gid）。
- 删除前必须先备份并校验：备份 hash 与当前文件不一致、备份缺失、或未勾选风险确认时，拒绝删除。
- 恢复从备份复制、恢复 `uid/gid` 与 `mode`，并重新计算 SHA-256 与清单比对；目标目录不存在时拒绝恢复。
- 只使用 Root 后端；Shizuku 是 shell 权限，不提供此模块的文件操作。
- 风险：删除温控/功耗参数可能导致发热、充电变慢、降频策略改变、系统不稳定；不保证提升性能；系统可能重新下发参数；没有备份或删错文件可能导致无法恢复。
- 不触碰 `com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL、内核 thermal zone。
```

- [ ] **Step 2: Expand `docs/03-design.md` §7.3**

Replace the current §7.3 bullet list with:

```markdown
### 7.3 温控 / COTA 模块（root-only）

- **实现状态（2026-11-21）**：代码完成，待实机验收（COTA1–COTA6）。
- 读取 `/data/cota/para` 下的普通文件（`find -maxdepth 1 -type f`，不递归、不跟随符号链接），显示名称、大小、mode/uid/gid、SHA-256。
- 已知文件：`cpuconfig`（CPU 调度参数）、`pg_config`（PowerGenie 功耗策略）、`thermal_config`（温控阈值）；同目录其他文件显示为“其他云控参数”。
- **备份路径**：`/storage/emulated/0/Nemu/cota-backup/<yyyyMMdd-HHmmss>/`（即 `/sdcard/Nemu/cota-backup/...`），每个备份包含文件副本和 `manifest.tsv`。
- **清单格式**：首行 `#nemu-cota-backup-v1`，之后每行 `name<TAB>sha256<TAB>size<TAB>mode<TAB>uid<TAB>gid`。
- **备份先行硬约束**：删除前必须存在该次备份的清单；选中文件必须全部在清单中；当前文件的 SHA-256 必须与清单一致。任一条件不满足即拒绝删除，不执行 `rm`。
- **删除**：只删除用户选中的文件，命令为 `rm -f` 加显式引用的绝对路径；不使用 `rm -rf`、通配符或递归。
- **恢复**：从备份目录 `cp -p` 到源路径，`chown` 恢复 uid/gid，`chmod` 恢复 mode，再计算 SHA-256 与清单比对；目标目录不存在时 fail-closed。
- **权限**：`CotaController` 的构造参数是 `RootPrivilegeBackend?`；`ShizukuBackend` 不实现该接口，因此不存在 Shizuku 回退。模块默认关闭（`Settings.cotaEnabled=false`）。
- **命令面**：`find`、`stat -c '%n|%s|%a|%u|%g'`、`sha256sum`、`mkdir`/`mkdir -p`、`cp -p`、`base64 -d`、`cat`、`rm -f`、`chown`、`chmod`。
- **不触碰**：`com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL、内核 thermal zone。
- **风险**：删除温控/功耗参数可能导致发热、充电变慢、降频策略改变、系统不稳定；不保证提升性能；系统可能重新下发参数；删错文件或无备份可能导致无法恢复；若删除导致 bootloop，fastboot 无法访问 `/data`，恢复手段有限。
```

In §7.4’s table, change the P4 row to:

```markdown
| P4 | COTA 备份/清理/恢复（root）、风险确认、导出日志；可选 Dhizuku/网络防火墙 | COTA 代码完成，待 COTA1–COTA6；其余未开始 |
```

- [ ] **Step 3: Add COTA1–COTA6 to `docs/04-test-plan.md`**

Insert after T10 and before “## 3. 验收标准”:

````markdown
### COTA1：COTA 只读清单（root）

**目的**：核对 App 展示的文件名、大小、mode/uid/gid、SHA-256 与设备实际内容一致。

1. 在 KSU 管理器中给 Nemu 授权 Root；打开 Nemu，启用“温控/COTA 模块”，点“刷新文件/备份”。
2. 在手机 root 终端执行（只读）：

   ```sh
   ls -la /data/cota/para
   stat -c '%n|%s|%a|%u|%g' /data/cota/para/*
   sha256sum /data/cota/para/*
   ls -Z /data/cota/para
   ```

3. 逐项对比 App 显示与终端输出。

**通过标准**：每个普通文件的名称、大小、SHA-256、mode、uid、gid 一致；子目录和符号链接不出现在可操作列表中；若 App 日志显示“跳过 N 个无法安全处理的条目”，用 root 终端对比 `find` 输出确认这些条目的名称，并记录数量与原因（App 出于安全考虑只记录数量，不回显不安全文件名）。

### COTA2：COTA 备份

**目的**：验证备份目录、清单和 hash 校验。

1. 在 App 中全选文件，点“备份选中”，记录时间戳 `<ts>`。
2. 在手机 root 终端执行：

   ```sh
   ls -la /storage/emulated/0/Nemu/cota-backup/<ts>/
   cat /storage/emulated/0/Nemu/cota-backup/<ts>/manifest.tsv
   sha256sum /storage/emulated/0/Nemu/cota-backup/<ts>/*
   ```

**通过标准**：备份目录包含所有选中文件和 `manifest.tsv`；清单首行为 `#nemu-cota-backup-v1`；备份文件 hash 与 COTA1 一致；App 显示“备份完成”。

### COTA3：COTA 合成文件备份/删除/恢复（先做，避开真实参数）

**目的**：在不碰真实云控参数的情况下验证删除与恢复路径。

1. 在手机 root 终端创建一个合成文件：

   ```sh
   printf 'nemu-test\n' > /data/cota/para/nemu_test_param
   chmod 644 /data/cota/para/nemu_test_param
   ```

2. 在 App 中刷新，只勾选 `nemu_test_param`，备份，然后删除（勾选风险确认）。
3. 在 root 终端验证删除：

   ```sh
   ls -la /data/cota/para
   test ! -e /data/cota/para/nemu_test_param && echo GONE
   ```

4. 在 App 中从该备份恢复，再验证：

   ```sh
   sha256sum /data/cota/para/nemu_test_param
   cat /data/cota/para/nemu_test_param
   stat -c '%a|%u|%g' /data/cota/para/nemu_test_param
   ```

**通过标准**：删除后文件不存在；恢复后内容为 `nemu-test`，hash 与清单一致，mode/uid/gid 与备份前一致。清理：用户确认后删除该合成文件。

### COTA4：真实参数删除（可选，高风险）

**前置**：COTA2 全量备份完成；手机电量 50%–80%；先做 COTA3。

1. 记录基线（PC，只读）：

   ```powershell
   adb shell dumpsys thermalservice
   adb shell dumpsys battery | Select-String 'temperature|status|level|health'
   adb shell dumpsys cpuinfo
   ```

2. 只删除 `cpuconfig`；不要同时删除 `thermal_config` 或 `pg_config`。
3. 删除后 10 分钟内每 2 分钟记录一次 `dumpsys battery` 温度与 `dumpsys thermalservice` 状态；观察是否异常发热、卡顿、充电异常。
4. 正常重启（电源菜单或 `svc power reboot`，不要用 `adb reboot`），确认：

   ```powershell
   adb shell getprop sys.boot_completed
   adb shell dumpsys thermalservice
   ```

5. 检查是否被重新下发（root 终端）：

   ```sh
   ls -la /data/cota/para
   sha256sum /data/cota/para/*
   ```

6. 从 COTA2 备份恢复 `cpuconfig`，确认 hash 与 COTA1 一致。

**通过标准**：正常开机；无持续热紧急状态；可恢复且 hash 一致。失败处理见“回滚”。

### COTA5：Root-only 守卫

**目的**：确认 Shizuku 或无 Root 时模块不可操作。

1. 重启设备但不加载 KSU late-load root；启动 Shizuku 并授权 Nemu。
2. 打开 Nemu，启用 COTA 模块。

**通过标准**：显示“当前没有 Root，模块不可用”；刷新/备份/删除/恢复按钮均不可用；App 日志中没有 COTA 命令。也可在 KSU 管理器中临时撤销 Nemu 的 root 授权复测。

### COTA6：系统重新下发观察（信息性）

删除真实参数后保持联网，等待 30 分钟并重启，重新执行 COTA1 的只读命令。记录文件是否重新出现、hash 是否变化。此项不设通过/失败，仅记录“系统可能重新下发”的实际行为。
````

Then in §3 “验收标准”, add these unchecked items:

```markdown
- [ ] COTA1 COTA 只读清单通过。
- [ ] COTA2 COTA 备份与清单校验通过。
- [ ] COTA3 合成文件备份/删除/恢复通过。
- [ ] COTA5 Root-only 守卫通过。
- [ ] COTA4 真实参数删除（可选）未执行或记录结果。
```

Then append a new §7.4:

```markdown
### 7.4 COTA 实测记录（待执行）

| 日期 | 测试 ID | 结果 | 备注 |
|---|---|---|---|
| 2026-11-21 | COTA1 COTA 只读清单 | 未测 | 需 Root（KSU late-load） |
| 2026-11-21 | COTA2 COTA 备份 | 未测 | 需 Root；记录备份时间戳 |
| 2026-11-21 | COTA3 合成文件删除/恢复 | 未测 | 先做此项，不碰真实参数 |
| 2026-11-21 | COTA4 真实参数删除 | 未测 | 可选；高风险 |
| 2026-11-21 | COTA5 Root-only 守卫 | 未测 | 无 Root + Shizuku |
| 2026-11-21 | COTA6 系统重新下发 | 未测 | 信息性 |
```

- [ ] **Step 4: Add the backup-first note to `docs/02-package-inventory.md` §5**

After the §5 table (before “**PowerKitService 的实际绑定方**”), add:

```markdown
**COTA 参数清理（root-only）**：`/data/cota/para` 下的 `cpuconfig`、`pg_config`、`thermal_config` 等是云控下发的性能/温控参数。任何删除都必须先备份到 `/storage/emulated/0/Nemu/cota-backup/<yyyyMMdd-HHmmss>/`，备份目录包含 `manifest.tsv`（SHA-256 + mode/uid/gid），删除前重新校验当前文件 hash 与清单一致。只删除选中的普通文件，不递归、不使用 `rm -rf`；恢复时按清单复制并校验。不要触碰 `com.hihonor.powergenie`、`com.hihonor.iaware`、vendor HAL 或内核 thermal zone。
```

- [ ] **Step 5: Run the full verification**

```powershell
cd D:\win_root\nemu
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks :app:assembleDebug
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
Select-String -Path app\src\main\java\com\nemu\hibernate\cota\*.kt -Pattern 'rm -rf|Shizuku|java\.io\.File|contentResolver'
Select-String -Path app\src\main\java\com\nemu\hibernate\cota\*.kt -Pattern 'powergenie|iaware|thermal_zone|/sys/class/thermal'
```

Expected: tests pass; build succeeds; the two `Select-String` commands return no matches; record the APK size and SHA-256 in the commit message or a scratch note (do not add a new report file).

- [ ] **Step 6: Commit documentation and plan**

```powershell
cd D:\win_root\nemu
git add README.md docs/02-package-inventory.md docs/03-design.md `
  docs/04-test-plan.md docs/superpowers/plans/2026-11-21-nemu-cota-thermal.md
git commit -m "docs: document COTA module and device test plan"
```

---

## Manual Device Test Plan (User-Executed)

The detailed steps are in `docs/04-test-plan.md` as COTA1–COTA6 after Task 5. The order is mandatory:

1. **COTA1 read-only inventory** — compare app output with `ls -la`, `stat`, `sha256sum`, `ls -Z` in a phone root terminal.
2. **COTA2 backup** — verify backup files and `manifest.tsv` hashes.
3. **COTA3 synthetic file** — create `nemu_test_param`, backup/delete/restore it; this proves the mechanics without touching real thermal params.
4. **COTA5 root guard** — boot without root, with Shizuku, and confirm the module refuses to operate.
5. **COTA4 real parameter (optional)** — delete only `cpuconfig`; monitor `dumpsys battery` / `dumpsys thermalservice`; reboot; restore; verify hash.
6. **COTA6 re-push observation** — record whether the system re-creates the file.

PC-side observation commands are read-only:

```powershell
adb shell dumpsys thermalservice
adb shell dumpsys battery | Select-String 'temperature|status|level|health'
adb shell dumpsys cpuinfo
adb shell getprop sys.boot_completed
```

Root-only commands (`ls /data/cota/para`, `sha256sum`, `stat`, `chown`, `rm`, `cp`) must be run in the phone's KSU manager / root terminal, because this device's `adb shell su` is unavailable.

**Do not run COTA4 on `thermal_config` or `pg_config` until COTA3 and a single `cpuconfig` deletion have passed.** The synthetic-file test exists specifically to avoid risking boot on the first attempt.

## Acceptance Criteria

- [ ] `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks` passes, including `ShellQuoteTest`, `CotaCommandBuilderTest`, `CotaParsersTest`, and `CotaControllerTest`.
- [ ] `.\gradlew.bat :app:assembleDebug` succeeds.
- [ ] `grep -rni "rm -rf" app/src` (or `Select-String`) returns no matches.
- [ ] `grep -rni "hail" app/src build.gradle.kts settings.gradle.kts` returns no matches.
- [ ] No file in the `com.nemu.hibernate.cota` package contains `Shizuku`, `java.io.File`, `contentResolver`, or `MANAGE_EXTERNAL_STORAGE`. (The UI may name Shizuku only in explanatory text that says it is not used.)
- [ ] `CotaController` accepts only `RootPrivilegeBackend?`; `ShizukuBackend` is not a `RootPrivilegeBackend`.
- [ ] `Settings.cotaEnabled` defaults to `false`; the UI shows the module as disabled on first run.
- [ ] The UI shows the heat/charging/throttling/re-push/no-guarantee warning and the full SHA-256 of each file.
- [ ] Delete is refused when: risk not acknowledged; no backup ID; selected file not in the manifest; current SHA-256 differs from the manifest; module disabled; no root.
- [ ] Restore verifies every restored file's SHA-256 against the manifest and fails closed when `/data/cota/para` is missing.
- [ ] Backup manifest is stored in the backup directory and verified by read-back.
- [ ] README, design §7.3/§7.4, test plan COTA1–COTA6, and package inventory §5 are updated.
- [ ] Device tests COTA1–COTA5 are marked `未测` until the user executes them; no result is recorded as passed without evidence.

## Rollback

### Code rollback

The module is additive and default-off. To remove it:

```powershell
cd D:\win_root\nemu
git log --oneline -6
git revert --no-edit <commit-for-task-5> <commit-for-task-4> <commit-for-task-3> <commit-for-task-2> <commit-for-task-1>
```

Alternatively, set `Settings.cotaEnabled = false` (the UI switch) to disable all COTA actions immediately; no background work uses COTA, so no other behavior changes.

### Device rollback

Preferred: open Nemu → 温控/COTA → choose the backup → “恢复”. The app runs:

```sh
cp -p '/storage/emulated/0/Nemu/cota-backup/<ts>/<name>' '/data/cota/para/<name>'
chown '<uid>:<gid>' '/data/cota/para/<name>'
chmod '<mode>' '/data/cota/para/<name>'
sha256sum '/data/cota/para/<name>'
```

Manual fallback in a phone root terminal, using values from `manifest.tsv`:

```sh
ls -la /storage/emulated/0/Nemu/cota-backup/
cat /storage/emulated/0/Nemu/cota-backup/<ts>/manifest.tsv
cp -p '/storage/emulated/0/Nemu/cota-backup/<ts>/cpuconfig' '/data/cota/para/cpuconfig'
chown '1000:1000' '/data/cota/para/cpuconfig'
chmod '644' '/data/cota/para/cpuconfig'
sha256sum '/data/cota/para/cpuconfig'
```

If the system is bootlooping after a deletion:

- Do not delete more files.
- Try a normal boot; if the system boots, use the KSU manager root terminal to restore from `/storage/emulated/0/Nemu/cota-backup/...`.
- `fastboot` cannot read or write `/data`; if Android never boots and root is unavailable, recovery may require a factory reset or service. The backup on `/storage/emulated/0` is erased by a factory reset — copy it off-device before any real-parameter test.
- This is why COTA3 uses a synthetic file and COTA4 deletes only one real file at a time.

## Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Deleting the wrong file | High — thermal/charging behavior changes | Fixed source dir; regular files only; explicit selection; safe-name regex; confirmation dialog; no globs/recursion |
| No backup before delete | High — cannot restore | Controller refuses delete without a manifest covering every selected file; hashes re-verified immediately before `rm`; manifest read-back verified |
| Thermal safety | High — overheating, charging slowdown, instability | Strong UI warning; one file at a time; synthetic-file test first; monitor `dumpsys battery`/`dumpsys thermalservice`; restore path |
| System re-push | Medium — deletion may be undone | Documented; COTA6 records behavior; no claim of a permanent performance change |
| Boot impact from deleted params | High — possible bootloop | Synthetic test first; never delete multiple real files at once; full backup; user keeps off-device copy; rollback section states the fastboot limitation |
| File changes between backup and delete | Medium — restoring a different version | Delete re-hashes current files and aborts on mismatch; user must re-backup |
| Backup storage full/unwritable | Medium — no verified backup | Backup command failure aborts before any delete; no fallback to deletion |
| Partial backup directory after a failed copy | Low — clutter, no restore | No manifest is written, so the controller cannot use it to authorize deletion; the user can inspect/remove it manually; the next timestamp creates a new directory |
| Command injection via a hostile filename | High — arbitrary root command execution | Names must match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`; every path is single-quoted; unsafe entries are skipped and logged; commands never embed raw `find` output — names are revalidated before being placed in a command |
| Accidentally touching powergenie/iaware/HAL/thermal zones | High — system-wide breakage | Every path argument is restricted to `/data/cota/para` and the backup root; no package commands and no sysfs paths; acceptance grep |
| SELinux context differs after restore | Medium — consumer may reject the file | Record `ls -Z` before/after in COTA1/COTA4; new files normally inherit the parent directory type; verify with COTA4 before relying on restore; reboot may regenerate parameters |
| Large file exceeds the 15s `su` timeout | Low/Medium — hash fails | Hash/stat commands chunk 8 paths; a single oversized file yields an explicit error and deletion is refused |
| App killed during delete | Medium — partial deletion | Only selected files are removed; backup remains; re-list on next launch; restore from manifest |
| User treats the module as a performance guarantee | Low — unmet expectation | UI text explicitly says no guarantee; system may re-push |

## Self-Review Notes

- **Spec coverage:** §7.3 listing/hash, backup to `/sdcard/Nemu/...`, selected delete after confirmation, restore, root-only, no powergenie/iaware/HAL/thermal-zone access, risk warning, default off, tests, docs — each maps to Task 1–5 or the manual test plan.
- **Placeholder scan:** no `TBD`, `TODO`, “implement later”, or unspecified behavior. Device results are explicitly `未测`, which is a truthful state, not a placeholder.
- **Type consistency:** `RootPrivilegeBackend`, `CotaController` signatures, `CotaFile`/`CotaBackupFile` field order, `CotaResult` branches, and command strings are consistent across Tasks 1–4 and the tests.
- **Backup-first invariant:** enforced in `CotaController.delete` by `loadBackup` + coverage check + current-hash comparison + `riskAcknowledged`; the UI additionally requires a backup ID before enabling the delete button.
