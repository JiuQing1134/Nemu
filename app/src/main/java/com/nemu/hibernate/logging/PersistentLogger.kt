package com.nemu.hibernate.logging

import android.app.Application
import android.content.Context
import android.os.Process
import com.nemu.hibernate.data.LogSettings
import com.nemu.hibernate.privilege.PrivilegeBackend
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 文件日志（docs/07 §7）。
 *
 * - 默认关闭；关闭时不创建目录、不写文件。
 * - 目录：`getExternalFilesDir(null)/logs`，不可用时回退 `filesDir/logs`。
 * - 当前文件 `nemu-YYYYMMDD.log`；单文件 2 MB，写满后轮转为
 *   `nemu-YYYYMMDD.<n>.log`；按修改时间最多保留 7 个 `nemu-*.log`。
 * - 行格式：`ISO-8601 时间 LEVEL  [tag] 消息`；消息中的换行转义为 `\n`，
 *   超过 1000 字符截断。
 * - 所有写入同步化；任何公开方法都不向调用方抛异常。
 *
 * 测试用 [initForTest] 直接指定目录和开关，避免依赖 Android `Context`。
 */
object PersistentLogger {

    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 7
    private const val MAX_STARTUP_FILES = 3
    private const val MAX_STARTUP_FILE_CHARS = 500_000
    private const val MAX_MESSAGE_CHARS = 1000
    private const val LOGCAT_COMMAND = "logcat -b all -d -t 3000"

    private val TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    private val DAY_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    private val FILE_STAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private val LOG_FILE_NAME = Regex("""nemu-\d{8}(?:\.\d+)?\.log""")
    private val STARTUP_FILE_NAME = Regex("""startup-\d{8}-\d{6}\.txt""")

    private val writeLock = Any()

    @Volatile
    private var enabled = false

    @Volatile
    private var dirs: List<File> = emptyList()

    val isEnabled: Boolean
        get() = enabled

    /** 读取 [LogSettings] 开关并解析日志目录；在 `Application.onCreate` 调用。 */
    fun init(context: Context) {
        val candidates = candidateDirs(context)
        val loggingEnabled = runCatching {
            LogSettings(context).loggingEnabled
        }.getOrDefault(false)
        synchronized(writeLock) {
            dirs = candidates
            enabled = loggingEnabled
        }
    }

    /** JVM 测试入口：直接指定日志根目录与开关，不触碰 Android API。 */
    fun initForTest(root: File, enabled: Boolean) {
        synchronized(writeLock) {
            dirs = listOf(root)
            this.enabled = enabled
        }
    }

    /** 只改内存开关；持久化由 [setEnabled] 的 Context 重载或 [LogSettings] 负责。 */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /** 持久化到 `logging_enabled` 并立即生效。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching { LogSettings(context).loggingEnabled = enabled }
        setEnabled(enabled)
    }

    /** 追加一行日志；关闭、目录不可写或任何 IO 失败时静默返回。 */
    fun log(level: String, tag: String, message: String) {
        if (!enabled) return
        val candidates = dirs
        if (candidates.isEmpty()) return
        synchronized(writeLock) {
            if (!enabled) return
            runCatching {
                val dir = writableDir(candidates) ?: return
                val now = OffsetDateTime.now()
                val bytes = (formatLine(now, level, tag, message) + "\n")
                    .toByteArray(Charsets.UTF_8)
                val current = File(
                    dir,
                    "nemu-${now.toLocalDate().format(DAY_FORMATTER)}.log"
                )
                if (current.isFile && current.length() > 0 &&
                    current.length() + bytes.size > MAX_FILE_BYTES
                ) {
                    rotate(current)
                }
                current.appendBytes(bytes)
                prune(dir)
            }
        }
    }

    /** 记录启动信息：版本、pid、boot count、进程名（docs/07 §7.3）。 */
    fun startup(context: Context) {
        val version = appVersion(context)
        val pid = Process.myPid()
        val bootCount = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT,
                -1
            )
        }.getOrDefault(-1)
        val process = runCatching { Application.getProcessName() }
            .getOrNull() ?: "unknown"
        log(
            "INFO",
            "startup",
            "pid=$pid boot=$bootCount version=$version process=$process"
        )
    }

    /** 最近日志行（按时间正序），跨轮转文件读取，最多 [max] 行。 */
    fun recentLines(max: Int): List<String> = recentLinesIn(dirs, max)

    /** 同上，但目录从 [context] 解析（UI 可在未依赖内存状态时使用）。 */
    fun recentLines(context: Context, max: Int): List<String> =
        recentLinesIn(candidateDirs(context), max)

    /** 日志目录内的 `nemu-*.log` 与 `startup-*.txt`，按修改时间倒序。 */
    fun files(): List<File> = listLogFiles(dirs)

    fun files(context: Context): List<File> =
        listLogFiles(candidateDirs(context))

    /** 删除本工具生成的日志文件（不删除其它文件）。 */
    fun clear() = deleteLogFiles(dirs)

    fun clear(context: Context) = deleteLogFiles(candidateDirs(context))

    /**
     * 用给定后端抓取启动 logcat（`logcat -b all -d -t 3000`），写入
     * `startup-YYYYMMDD-HHmmss.txt`。成功返回绝对路径，失败返回可展示的错误文本。
     * 这是用户显式操作，因此不受日志开关限制。
     */
    suspend fun captureLogcat(context: Context, backend: PrivilegeBackend?): String {
        if (backend == null) {
            val message = "抓取 logcat 失败：无可用 Root/Shizuku 后端"
            log("WARN", "logcat", message)
            return message
        }
        val result = runCatching { backend.exec(LOGCAT_COMMAND) }
            .getOrElse { error ->
                val message =
                    "抓取 logcat 失败：${error.message ?: error.javaClass.simpleName}"
                log("WARN", "logcat", message)
                return message
            }
        if (!result.ok) {
            val message = "抓取 logcat 失败：exit=${result.exitCode}"
            log("WARN", "logcat", message)
            return message
        }
        return runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dir = writableDir(candidateDirs(context))
                    ?: error("日志目录不可写")
                val now = OffsetDateTime.now()
                val name = "startup-${now.format(FILE_STAMP_FORMATTER)}.txt"
                val file = File(dir, name)
                val captured = if (result.output.length <= MAX_STARTUP_FILE_CHARS) {
                    result.output
                } else {
                    "[earlier logcat lines truncated]\n" +
                        result.output.takeLast(MAX_STARTUP_FILE_CHARS)
                }
                file.writeText(captured)
                prune(dir)
                log("INFO", "logcat", "captured $name exit=${result.exitCode}")
                file.absolutePath
            }
        }.getOrElse { error ->
            val message =
                "抓取 logcat 失败：${error.message ?: error.javaClass.simpleName}"
            log("WARN", "logcat", message)
            message
        }
    }

    private fun formatLine(
        now: OffsetDateTime,
        level: String,
        tag: String,
        message: String
    ): String {
        val levelField = level.uppercase(Locale.ROOT).padEnd(5)
        val tagField = tag.replace('\n', ' ').replace('\r', ' ')
        return "${now.format(TIMESTAMP_FORMATTER)} $levelField " +
            "[$tagField] ${sanitize(message)}"
    }

    private fun sanitize(message: String): String {
        val oneLine = message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\\n")
        return if (oneLine.length <= MAX_MESSAGE_CHARS) {
            oneLine
        } else {
            oneLine.take(MAX_MESSAGE_CHARS) + "…"
        }
    }

    private fun rotate(file: File) {
        val base = file.name.removeSuffix(".log")
        var index = 1
        var target = File(file.parentFile, "$base.$index.log")
        while (target.exists()) {
            index += 1
            target = File(file.parentFile, "$base.$index.log")
        }
        val renamed = runCatching { file.renameTo(target) }.getOrDefault(false)
        if (!renamed) {
            runCatching {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        val logs = files.filter { it.isFile && LOG_FILE_NAME.matches(it.name) }
        if (logs.size > MAX_FILES) {
            logs.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name }
            )
                .drop(MAX_FILES)
                .forEach { file -> runCatching { file.delete() } }
        }

        files.filter { it.isFile && STARTUP_FILE_NAME.matches(it.name) }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenByDescending { it.name }
            )
            .drop(MAX_STARTUP_FILES)
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun recentLinesIn(candidates: List<File>, max: Int): List<String> {
        if (max <= 0) return emptyList()
        return runCatching {
            val files = listLogFiles(candidates)
                .filter { LOG_FILE_NAME.matches(it.name) }
            if (files.isEmpty()) return emptyList()
            val tail = ArrayDeque<String>()
            // Oldest first so the deque ends up in chronological order; the
            // size cap keeps memory bounded to [max] lines.
            for (file in files.asReversed()) {
                file.useLines { lines ->
                    for (line in lines) {
                        tail.addLast(line)
                        if (tail.size > max) tail.removeFirst()
                    }
                }
            }
            tail.toList()
        }.getOrDefault(emptyList())
    }

    private fun listLogFiles(candidates: List<File>): List<File> =
        runCatching {
            candidates.asSequence()
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.listFiles()?.asSequence() ?: emptySequence()
                }
                .filter { it.isFile && isLogFileName(it.name) }
                .sortedByDescending { it.lastModified() }
                .toList()
        }.getOrDefault(emptyList())

    private fun deleteLogFiles(candidates: List<File>) {
        runCatching {
            candidates.filter { it.isDirectory }.forEach { dir ->
                dir.listFiles()
                    ?.filter { it.isFile && isLogFileName(it.name) }
                    ?.forEach { file -> file.delete() }
            }
        }
    }

    private fun isLogFileName(name: String): Boolean =
        LOG_FILE_NAME.matches(name) || STARTUP_FILE_NAME.matches(name)

    private fun candidateDirs(context: Context): List<File> {
        val candidates = ArrayList<File>(2)
        runCatching { context.getExternalFilesDir(null) }
            .getOrNull()
            ?.let { candidates.add(File(it, "logs")) }
        candidates.add(File(context.filesDir, "logs"))
        return candidates
    }

    private fun writableDir(candidates: List<File>): File? {
        for (candidate in candidates) {
            if (candidate.isDirectory) return candidate
            val created = runCatching { candidate.mkdirs() }.getOrDefault(false)
            if (created || candidate.isDirectory) return candidate
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull() ?: "unknown"
}
