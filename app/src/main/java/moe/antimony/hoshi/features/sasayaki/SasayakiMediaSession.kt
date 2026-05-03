package moe.antimony.hoshi.features.sasayaki

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import moe.antimony.hoshi.R
import java.io.File
import kotlin.math.max

class SasayakiMediaSession(
    context: Context,
    private val title: String,
    private val artwork: Bitmap?,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onSkipToPrevious: () -> Unit,
    private val onSkipToNext: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val session = MediaSession(context.applicationContext, "Hoshi Sasayaki")
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private var isPlaying = false
    private var notificationPlaying: Boolean? = null
    private var hasPublishedNotification = false

    init {
        ensureNotificationChannel()
        session.setSessionActivity(contentIntent())
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    onPlay.invoke()
                }

                override fun onPause() {
                    onPause.invoke()
                }

                override fun onSkipToPrevious() {
                    onSkipToPrevious.invoke()
                }

                override fun onSkipToNext() {
                    onSkipToNext.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    onSeekTo.invoke(pos)
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    fun activate() {
        session.isActive = true
        publishNotification()
    }

    fun update(
        isPlaying: Boolean,
        currentTimeMs: Long,
        durationMs: Long,
        rate: Float,
    ) {
        this.isPlaying = isPlaying
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
        artwork?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
        }
        session.setMetadata(metadata.build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackActions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    currentTimeMs.coerceAtLeast(0L),
                    if (isPlaying) rate else 0f,
                )
                .build(),
        )
        if ((session.isActive || hasPublishedNotification) && notificationPlaying != isPlaying) {
            publishNotification()
        }
    }

    fun release() {
        session.isActive = false
        notificationManager.cancel(NotificationId)
        session.release()
    }

    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                "Sasayaki Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    @SuppressLint("NotificationPermission")
    private fun publishNotification() {
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        val builder = Notification.Builder(appContext, ChannelId)
            .setSmallIcon(R.drawable.ic_stat_hoshi)
            .setContentTitle(title)
            .setContentText("Sasayaki")
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(style)
        artwork?.let { builder.setLargeIcon(artwork) }
        val notification = builder.build()
        notificationManager.notify(NotificationId, notification)
        notificationPlaying = isPlaying
        hasPublishedNotification = true
    }

    private fun contentIntent(): PendingIntent? =
        appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            ?.let { intent ->
                PendingIntent.getActivity(
                    appContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

    companion object {
        private const val ChannelId = "sasayaki_playback"
        private const val NotificationId = 2407
        private const val MaxArtworkDimensionPx = 900
        private const val PlaybackActions =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SEEK_TO

        fun loadCoverArt(file: File?): Bitmap? {
            file?.takeIf { it.isFile } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sampleSize = coverDecodeSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimensionPx = MaxArtworkDimensionPx,
            )
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeFile(file.absolutePath, options)
        }

        private fun coverDecodeSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
            if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
            var sampleSize = 1
            while (max(width / sampleSize, height / sampleSize) > maxDimensionPx) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
