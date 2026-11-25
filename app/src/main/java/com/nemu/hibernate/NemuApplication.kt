package com.nemu.hibernate

import android.app.Application
import android.content.Context
import android.os.Process
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.logging.PersistentLogger
import java.io.File

/**
 * 进程入口：初始化文件日志并记录一次启动。不启动服务、不请求权限。
 * 由主会话在 AndroidManifest.xml 中注册 `android:name=".NemuApplication"`。
 */
class NemuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        // Direct boot can start this process for LOCKED_BOOT_COMPLETED while
        // credential-encrypted prefs are unavailable. All Settings access is
        // inside reloadAfterUnlock's runCatching and runs again on unlock.
        reloadAfterUnlock(this)
        PersistentLogger.startup(this)
        // Cold start after a process kill between locked boot and unlock:
        // resume the boot-safety pass the runtime receiver never saw.
        runCatching { com.nemu.hibernate.policy.BootReceiver.ensureBootSafetyIfPending(this) }
    }

    /**
     * Keep the last uncaught exception in app-private storage so an
     * intermittent crash can be diagnosed without a connected PC. The
     * previous handler still runs and terminates the process normally.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = buildString {
                    append("time=")
                    append(System.currentTimeMillis())
                    append("\nthread=")
                    append(thread.name)
                    append('\n')
                    append(error.stackTraceToString())
                }
                File(filesDir, "last-crash.txt").writeText(trace)
                PersistentLogger.log("ERROR", "crash", trace)
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                runCatching { Process.killProcess(Process.myPid()) }
            }
        }
    }

    companion object {
        /**
         * Loads credential-encrypted state. Application.onCreate can run in
         * direct boot, before the user unlocks; USER_UNLOCKED and
         * BOOT_COMPLETED call this again before the boot worker reads the
         * catalog or logging settings. All reads fail soft.
         */
        fun reloadAfterUnlock(context: Context) {
            val appContext = context.applicationContext ?: context
            runCatching {
                val settings = Settings(appContext)
                // Seed before any screen reads dryRun; old builds never
                // persisted the key, so an upgrade must keep real execution.
                settings.ensureDryRunDefault()
                TargetCatalog.setCustomTargets(
                    settings.customTargets().map { record ->
                        TargetCatalog.customTarget(
                            record.packageName,
                            record.label,
                            record.risk
                        )
                    }
                )
            }
            runCatching { PersistentLogger.init(appContext) }
        }
    }
}
