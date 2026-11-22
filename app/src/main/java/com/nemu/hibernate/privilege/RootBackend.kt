package com.nemu.hibernate.privilege

import com.nemu.hibernate.core.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

class RootBackend(private val timeoutMs: Long = 15_000) : RootPrivilegeBackend {
    override val name: String = "Root"
    override val canSetHidden: Boolean = true

    // FinaSU/KernelSU keeps su under /data/adb/ksu/bin; it is not always in
    // PATH for third-party apps. Try the standard locations in order.
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/data/adb/ksu/bin/su"
    )

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val result = execRaw("id")
            result.exitCode == 0 && result.output.contains("uid=0")
        }.getOrDefault(false)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        execRaw(command)
    }

    private fun execRaw(command: String): ShellResult {
        var lastError: Throwable? = null
        for (candidate in suCandidates) {
            try {
                return runSu(candidate, command)
            } catch (io: IOException) {
                // Candidate not present in PATH; try the next one.
                lastError = io
            } catch (throwable: Throwable) {
                return ShellResult(-1, throwable.stackTraceToString())
            }
        }
        return ShellResult(
            -1,
            "su not found; tried ${suCandidates.joinToString()}: " +
                (lastError?.message ?: "unknown error")
        )
    }

    private fun runSu(su: String, command: String): ShellResult {
        val process = ProcessBuilder(su).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(command)
            writer.newLine()
        }
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            return ShellResult(-1, "timeout after ${timeoutMs}ms")
        }
        reader.join(1000)
        return ShellResult(process.exitValue(), output.toString())
    }
}
