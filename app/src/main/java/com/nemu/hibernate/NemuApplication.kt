package com.nemu.hibernate

import android.app.Application
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
        // Custom targets must exist before any worker/safety path reads the
        // catalog. SharedPreferences are available here (post-unlock).
        runCatching {
            TargetCatalog.setCustomTargets(
                Settings(this).customTargets().map { (pkg, label) ->
                    TargetCatalog.customTarget(pkg, label)
                }
            )
        }
        PersistentLogger.init(this)
        PersistentLogger.startup(this)
    }
}
