package de.kilian.applimit.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import de.kilian.applimit.domain.WatchdogPolicy
import java.util.concurrent.TimeUnit

class AccessibilityWatchdogWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val now = System.currentTimeMillis()
        val healthStore = ServiceHealthStore(applicationContext)
        val accessibilityEnabled = AccessibilityStatus.isEnabled(applicationContext)
        val serviceResponsive = accessibilityEnabled && AccessibilityServiceHealthProbe.ping()
        val evaluation = WatchdogPolicy.evaluate(
            healthStore.snapshot().toWatchdogInput(
                accessibilityEnabled = accessibilityEnabled,
                serviceResponsive = serviceResponsive,
                nowEpochMillis = now,
            ),
        )
        val notifier = RobustnessNotifier(applicationContext).also { it.createChannels() }
        if (evaluation.isHealthy) {
            notifier.cancelWatchdogIssue()
        } else {
            notifier.notifyWatchdogIssue(requireNotNull(evaluation.issue))
        }
        return Result.success()
    }
}

object WatchdogScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(false)
        .setRequiresCharging(false)
        .setRequiresDeviceIdle(false)
        .setRequiresStorageNotLow(false)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<AccessibilityWatchdogWorker>(
            15,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleBootCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<AccessibilityWatchdogWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BOOT_CHECK_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private const val PERIODIC_WORK_NAME = "accessibility-watchdog-periodic"
    private const val BOOT_CHECK_WORK_NAME = "accessibility-watchdog-after-boot"
}
