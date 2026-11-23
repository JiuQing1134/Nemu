package com.nemu.hibernate.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import com.nemu.hibernate.NemuApplication
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED is not delivered to Nemu on this OEM. LOCKED_BOOT_COMPLETED
        // registers a runtime USER_UNLOCKED receiver (the manifest one cannot
        // receive this registered-only broadcast) and then enqueues the safety
        // worker after unlock; MY_PACKAGE_REPLACED is a later fallback.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val appContext = context.applicationContext ?: context
            // Direct boot has no credential-encrypted prefs and WorkManager
            // may not be usable yet. Persist a device-protected flag so a
            // process kill before unlock still triggers the safety pass when
            // the app next starts.
            markPendingBootSafety(appContext)
            // ACTION_USER_UNLOCKED is registered-only and never reaches a
            // manifest receiver. The process is alive here, so register a
            // runtime receiver now and enqueue the safety worker on unlock.
            val unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(unlocked: Context, event: Intent) {
                    if (event.action != Intent.ACTION_USER_UNLOCKED) return
                    runCatching { unlocked.unregisterReceiver(this) }
                    unlockAfterDirectBoot(unlocked)
                }
            }
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    unlockReceiver,
                    IntentFilter(Intent.ACTION_USER_UNLOCKED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.isSuccess
            // The delayed LOCKED_BOOT broadcast can arrive after the user
            // already unlocked; in that case USER_UNLOCKED is gone.
            if (registered && (appContext.getSystemService(
                    Context.USER_SERVICE
                ) as? UserManager)?.isUserUnlocked == true
            ) {
                runCatching { appContext.unregisterReceiver(unlockReceiver) }
                unlockAfterDirectBoot(appContext)
            }
            return
        }

        // The process may have started in direct boot, when Application.onCreate
        // could not read credential-encrypted custom targets or log settings.
        // Reload them before any worker/safety path reads the catalog.
        NemuApplication.reloadAfterUnlock(context)

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // An APK update is not a reboot. Running the full boot safety pass
            // here used to unsuspend every target after every install, making
            // "frozen" apps silently active again. Cancel any pending safety
            // work (WorkManager persists it across updates) and only re-arm
            // the service if something is still frozen.
            WorkManager.getInstance(context)
                .cancelUniqueWork("nemu_safety_unfreeze")
            SafetyService.sync(context)
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }

        enqueueBootSafety(context)
    }

    companion object {
        private const val DEVICE_PREFS = "nemu_device"
        private const val KEY_PENDING_BOOT_SAFETY = "pending_boot_safety"
        private const val SAFETY_WORK_NAME = "nemu_safety_unfreeze"

        private fun devicePrefs(context: Context): SharedPreferences? =
            runCatching {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
            }.getOrNull()

        private fun markPendingBootSafety(context: Context) {
            runCatching {
                devicePrefs(context)
                    ?.edit()
                    ?.putBoolean(KEY_PENDING_BOOT_SAFETY, true)
                    ?.commit()
            }
        }

        internal fun clearPendingBootSafety(context: Context) {
            runCatching {
                devicePrefs(context)
                    ?.edit()
                    ?.putBoolean(KEY_PENDING_BOOT_SAFETY, false)
                    ?.commit()
            }
        }

        /** Runs the pending pass after a kill-before-unlock boot, if any. */
        fun ensureBootSafetyIfPending(context: Context) {
            val appContext = context.applicationContext ?: context
            val pending = runCatching {
                devicePrefs(appContext)
                    ?.getBoolean(KEY_PENDING_BOOT_SAFETY, false) == true
            }.getOrDefault(false)
            if (!pending) return
            val unlocked = runCatching {
                (appContext.getSystemService(Context.USER_SERVICE)
                    as? UserManager)?.isUserUnlocked == true
            }.getOrDefault(false)
            if (!unlocked) return
            NemuApplication.reloadAfterUnlock(appContext)
            // Never let a WorkManager failure crash the app at cold start;
            // the device-protected flag stays set so the next attempt retries.
            runCatching { enqueueBootSafety(appContext) }
        }

        private fun unlockAfterDirectBoot(context: Context) {
            val appContext = context.applicationContext ?: context
            NemuApplication.reloadAfterUnlock(appContext)
            enqueueBootSafety(appContext)
        }

        private fun enqueueBootSafety(context: Context) {
            val appContext = context.applicationContext ?: context
            // Bring the runtime-receiver service up early only when a frozen
            // target actually exists. Background FGS starts may be refused on
            // newer Android; SafetyService.start() swallows it and BootWorker
            // will sync again after the boot unfreeze.
            SafetyService.sync(appContext)

            val workManager = WorkManager.getInstance(appContext)
            // Cancel the legacy auto-apply work from the previous version.
            workManager.cancelUniqueWork("nemu_boot_apply")

            val request = OneTimeWorkRequestBuilder<BootWorker>()
                .setInputData(
                    Data.Builder()
                        .putInt(
                            BootWorker.KEY_SCHEDULED_BOOT_COUNT,
                            BootCount.current(appContext)
                        )
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            val operation = workManager.enqueueUniqueWork(
                SAFETY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            // Clear only after WorkManager has actually persisted the work.
            // Clearing synchronously would lose the crash fallback if the
            // process died before the async enqueue completed; BootWorker also
            // clears at start as a second line of defence.
            operation.result.addListener(
                {
                    if (runCatching { operation.result.get() }.isSuccess) {
                        clearPendingBootSafety(appContext)
                    }
                },
                java.util.concurrent.Executor { it.run() }
            )
        }
    }
}
