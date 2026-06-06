package moe.antimony.hoshi.features.update

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
import androidx.hilt.work.HiltWorker
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.IoDispatcher
import java.util.concurrent.TimeUnit

@Singleton
internal class UpdateScheduler @Inject constructor(
    private val updateSettingsRepository: UpdateSettingsRepository,
    private val workManager: Lazy<WorkManager>,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    fun sync() {
        appScope.launch(ioDispatcher) {
            syncNow()
        }
    }

    suspend fun syncNow() {
        val enabled = updateSettingsRepository.settings.first().autoCheckUpdates
        if (enabled) {
            schedule()
            scheduleImmediateCheck()
        } else {
            cancel()
        }
    }

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.get().enqueueUniquePeriodicWork(
            UniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.get().cancelUniqueWork(UniqueWorkName)
        workManager.get().cancelUniqueWork(UniqueImmediateWorkName)
    }

    fun scheduleImmediateCheck() {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(networkConstraints())
            .build()
        workManager.get().enqueueUniqueWork(
            UniqueImmediateWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        const val UniqueWorkName = "github-release-update-check"
        const val UniqueImmediateWorkName = "github-release-update-check-now"
    }
}

@HiltWorker
internal class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val updateSettingsRepository: UpdateSettingsRepository,
    private val updateCheckService: UpdateCheckService,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val enabled = updateSettingsRepository.settings.first().autoCheckUpdates
        if (!enabled) return Result.success()
        return runCatching {
            updateCheckService.check(notifyAvailable = true)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
