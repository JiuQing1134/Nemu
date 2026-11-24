package com.nemu.hibernate

import android.app.Application
import android.content.Context
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.Settings
import com.nemu.hibernate.logging.PersistentLogger

/**
 * 进程入口：初始化文件日志并记录一次启动。不启动服务、不请求权限。
 * 由主会话在 AndroidManifest.xml 中注册 `android:name=".NemuApplication"`。
 */
class NemuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Direct boot can start this process for LOCKED_BOOT_COMPLETED while
        // credential-encrypted prefs are unavailable. All Settings access is
        // inside reloadAfterUnlock's runCatching and runs again on unlock.
        reloadAfterUnlock(this)
        PersistentLogger.startup(this)
        // Cold start after a process kill between locked boot and unlock:
        // resume the boot-safety pass the runtime receiver never saw.
        runCatching { com.nemu.hibernate.policy.BootReceiver.ensureBootSafetyIfPending(this) }
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
                runCatching {
                    val info = appContext.packageManager.getPackageInfo(
                        appContext.packageName,
                        0
                    )
                    settings.ensureOnboardingDefault(
                        info.firstInstallTime,
                        info.lastUpdateTime
                    )
                }
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
