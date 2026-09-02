package app.syncheroic.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.syncheroic.SyncHeroicApplication
import app.syncheroic.data.FrequentSyncSettings
import app.syncheroic.network.TrainHeroicException
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SyncHeroicApplication
        if (app.container.credentialVault.load() == null) return Result.success()
        val permissions = runCatching { app.container.healthConnectGateway.grantedPermissions() }.getOrDefault(emptySet())
        if (!permissions.containsAll(app.container.healthConnectGateway.requiredPermissions)) return Result.success()

        val mode = inputData.getString(KEY_MODE) ?: MODE_DAILY
        if (mode == MODE_FREQUENT) {
            val settings = app.container.stateStore.frequentSyncSettings()
            if (!settings.enabled || !isWithinFrequentWindow(LocalTime.now(), settings)) return Result.success()
        }
        return runCatching {
            val end = LocalDate.now()
            val start = if (mode == MODE_DAILY) end.minusDays(7) else end.minusDays(1)
            app.container.syncCoordinator.automaticSync(start, end, refreshRemoteConfig = mode == MODE_DAILY)
            Result.success()
        }.getOrElse(::failureResult)
    }

    private fun failureResult(error: Throwable): Result {
        val clientError = error as? TrainHeroicException
        if (clientError?.status in 400..499) return Result.failure()
        val transient = error is IOException || (clientError?.status ?: 0) >= 500
        return if (transient && runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_MODE = "sync_mode"
        const val MODE_DAILY = "daily"
        const val MODE_FREQUENT = "frequent"
        const val MODE_RECENT = "recent"

        fun isWithinFrequentWindow(time: LocalTime, settings: FrequentSyncSettings): Boolean =
            !time.isBefore(settings.start) && !time.isAfter(settings.end)
    }
}

object SyncScheduler {
    private const val DAILY_NAME = "daily-sync"
    private const val FREQUENT_NAME = "frequent-sync"
    private const val FOREGROUND_NAME = "foreground-sync"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun scheduleDaily(context: Context) {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().atTime(4, 0).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, target))
            .setInputData(mode(SyncWorker.MODE_DAILY))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun configureFrequent(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(FREQUENT_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(mode(SyncWorker.MODE_FREQUENT))
            .setConstraints(constraints)
            .build()
        manager.enqueueUniquePeriodicWork(FREQUENT_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun enqueueForeground(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(mode(SyncWorker.MODE_RECENT))
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FOREGROUND_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun mode(value: String) = androidx.work.Data.Builder().putString(SyncWorker.KEY_MODE, value).build()
}
