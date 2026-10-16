package moe.antimony.hoshi.features.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R

private const val SetupAudioWorkName = "setup-offline-audio"
private const val SetupAudioChannelId = "hoshi_setup_downloads"
private const val SetupAudioNotificationId = 4107

/** Queues the offline word-audio download. It survives leaving the app and resumes after a retry. */
internal fun enqueueSetupAudioDownload(context: Context) {
    val request = OneTimeWorkRequestBuilder<SetupAudioWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(SetupAudioWorkName, ExistingWorkPolicy.KEEP, request)
}

/**
 * Downloads the multi-gigabyte audio database as a foreground `dataSync` job. Without the
 * notification permission the job still runs; Android only hides its progress notification.
 */
@HiltWorker
internal class SetupAudioWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SetupRepository,
) : CoroutineWorker(appContext, params) {
    private var lastPercent = -1

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(null))
        return try {
            repository.downloadAudio { progress ->
                val percent = progress.percent ?: return@downloadAudio
                if (percent == lastPercent) return@downloadAudio
                lastPercent = percent
                setForegroundAsync(foregroundInfo(percent))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SetupFailure) {
            if (error.reason == SetupFailure.Reason.Unreachable) Result.retry() else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(percent: Int?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SetupAudioChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SetupAudioChannelId,
                    applicationContext.getString(R.string.setup_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, SetupAudioChannelId)
            .setSmallIcon(R.drawable.ic_stat_hoshi)
            .setContentTitle(applicationContext.getString(R.string.setup_notification_audio_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SetupAudioNotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SetupAudioNotificationId, notification)
        }
    }
}
