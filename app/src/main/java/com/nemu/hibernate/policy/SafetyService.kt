package com.nemu.hibernate.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nemu.hibernate.core.TargetCatalog
import com.nemu.hibernate.data.ActionLog

/**
 * Safety service. It exists only while at least one target is actually
 * frozen: with nothing to unfreeze there is no reason to keep a foreground
 * process alive. The manifest [ShutdownReceiver] stays as a fallback for the
 * tiny window before the service is started.
 */
class SafetyService : Service() {
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN &&
                intent.action != Intent.ACTION_REBOOT
            ) {
                return
            }

            val pending = goAsync()
            ShutdownSafety.runAsync(applicationContext) { pending.finish() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The service is started with startService(), never
        // startForegroundService(): a background start is rejected at the
        // call site, so Android cannot kill the UI process later with
        // ForegroundServiceDidNotStartInTimeException when the main thread
        // was busy composing the catalog. Foreground promotion is still
        // best-effort and a failure only stops this service.
        val foregroundReady = runCatching {
            createChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
            true
        }.getOrElse { error ->
            ActionLog.warn(
                "安全解冻服务无法进入前台：${error.message ?: error.javaClass.simpleName}"
            )
            false
        }
        if (!foregroundReady) {
            runCatching { stopSelf() }
            return
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Intent.ACTION_REBOOT)
            }
            ContextCompat.registerReceiver(
                this,
                shutdownReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure {
            ActionLog.warn(
                "安全解冻广播注册失败：${it.message ?: it.javaClass.simpleName}"
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Do not ask the system to restart an empty service after a process
        // kill: the boot guard and the next app launch re-evaluate whether a
        // safety service is actually needed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(shutdownReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nemu 安全解冻",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "仅在存在冻结应用时运行；关机/重启前恢复 suspend 与组件；" +
                "没有冻结应用时本服务不会启动"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.nemu.hibernate.R.drawable.ic_notification)
            .setLargeIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this,
                    com.nemu.hibernate.R.drawable.ic_launcher_large
                )
            )
            .setContentTitle("Nemu 正在保护冻结状态")
            .setContentText("关机/重启前会自动解冻；没有冻结应用时本服务不会运行")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.nemu.hibernate.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    companion object {
        private const val CHANNEL_ID = "nemu_safety"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            // Deliberately a plain service start. From the foreground this is
            // allowed and the service promotes itself; from a boot receiver /
            // worker Android rejects it immediately and the boot guard remains
            // the shutdown fallback. A rejected start can never turn into an
            // FGS timeout after 5 seconds.
            runCatching {
                context.startService(
                    Intent(context, SafetyService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, SafetyService::class.java))
            }
        }

        /**
         * Starts or stops the safety service based on actual ownership. Called
         * after a freeze/restore and on app launch. With no frozen target the
         * app keeps no foreground process and no wakeups.
         */
        fun sync(context: Context) {
            val hasFrozen = runCatching {
                val policy = TargetPolicy(context)
                TargetCatalog.all.any { policy.isApplied(it) }
            }.getOrDefault(false)
            if (hasFrozen) start(context) else stop(context)
        }
    }
}
