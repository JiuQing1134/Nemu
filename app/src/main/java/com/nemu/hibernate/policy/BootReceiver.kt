package com.nemu.hibernate.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED is not delivered to Nemu on this OEM. LOCKED_BOOT_COMPLETED
        // is the direct-boot fallback that re-arms the safety service before the
        // user unlocks; USER_UNLOCKED and MY_PACKAGE_REPLACED are later fallbacks.
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Direct boot: credential-encrypted SharedPreferences are not
            // available yet, so do not evaluate the freeze policy here. The
            // boot worker runs after unlock and syncs the safety service.
            return
        }
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

        // Bring the runtime-receiver service up early only when a frozen
        // target actually exists. Background FGS starts may be refused on
        // newer Android; SafetyService.start() swallows it and BootWorker
        // will sync again after the boot unfreeze.
        SafetyService.sync(context)

        val workManager = WorkManager.getInstance(context)
        // Cancel the legacy auto-apply work from the previous version.
        workManager.cancelUniqueWork("nemu_boot_apply")

        val request = OneTimeWorkRequestBuilder<BootWorker>()
            .setInputData(
                Data.Builder()
                    .putInt(
                        BootWorker.KEY_SCHEDULED_BOOT_COUNT,
                        BootCount.current(context)
                    )
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            "nemu_safety_unfreeze",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
