package app.syncheroic.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.syncheroic.SyncHeroicApplication
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SyncHeroicApplication
        if (app.container.credentialVault.load() == null) return Result.success()
        val permissions = runCatching { app.container.healthConnectGateway.grantedPermissions() }.getOrDefault(emptySet())
        if (!permissions.containsAll(app.container.healthConnectGateway.requiredPermissions)) return Result.success()
        return runCatching {
            val end = LocalDate.now()
            val preview = app.container.syncCoordinator.preview(end.minusDays(7), end)
            app.container.syncCoordinator.apply(preview)
            Result.success()
        }.getOrElse { error ->
            if (runAttemptCount < 3 && error !is IllegalStateException) Result.retry() else Result.failure()
        }
    }
}

object SyncScheduler {
    private const val NAME = "daily-sync"

    fun schedule(context: Context) {
        val now = ZonedDateTime.now()
        var target = now.toLocalDate().atTime(4, 0).atZone(now.zone)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, target))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
