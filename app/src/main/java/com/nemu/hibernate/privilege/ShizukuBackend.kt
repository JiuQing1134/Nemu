package com.nemu.hibernate.privilege

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.nemu.hibernate.core.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ShizukuBackend(private val timeoutMs: Long = 15_000) : PrivilegeBackend {
    override val name: String = "Shizuku"

    /**
     * Only a Shizuku server started by root (uid 0) can call `pm hide`;
     * ADB-started Shizuku runs as uid 2000 and lacks MANAGE_USERS.
     */
    override val canSetHidden: Boolean
        get() = runCatching {
            Shizuku.pingBinder() && Shizuku.getUid() == 0
        }.getOrDefault(false)

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        runCatching {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(arrayOf("sh"), null, null)
            ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).use { stream ->
                stream.write(command.toByteArray())
            }

            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val stdoutReader = Thread {
                runCatching {
                    val text = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                        .bufferedReader()
                        .use { it.readText() }
                    stdoutRef.set(text)
                }
            }.apply {
                isDaemon = true
                start()
            }
            val stderrReader = Thread {
                runCatching {
                    val text = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                        .bufferedReader()
                        .use { it.readText() }
                    stderrRef.set(text)
                }
            }.apply {
                isDaemon = true
                start()
            }

            val exitRef = AtomicInteger(-1)
            val done = CountDownLatch(1)
            Thread {
                runCatching { exitRef.set(process.waitFor()) }
                done.countDown()
            }.apply {
                isDaemon = true
                start()
            }

            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // Binder destruction itself may stall; never hold the
                // shutdown broadcast thread waiting for it.
                Thread { runCatching { process.destroy() } }
                    .apply { isDaemon = true; start() }
                stdoutReader.join(250)
                stderrReader.join(250)
                ShellResult(-1, "timeout after ${timeoutMs}ms")
            } else {
                stdoutReader.join(1000)
                stderrReader.join(1000)
                ShellResult(exitRef.get(), stdoutRef.get().ifBlank { stderrRef.get() })
            }
        }.getOrElse { throwable ->
            ShellResult(-1, throwable.stackTraceToString())
        }
    }
}
