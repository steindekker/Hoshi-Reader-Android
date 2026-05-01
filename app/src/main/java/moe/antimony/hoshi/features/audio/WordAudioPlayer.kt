package moe.antimony.hoshi.features.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build

class WordAudioPlayer private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    fun play(url: String, mode: AudioPlaybackMode) {
        stop()
        if (!requestFocus(mode)) return
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ ->
                stop()
                true
            }
        }
        runCatching {
            val localFile = LocalAudioResolver.parseAudioUrl(url)
            if (localFile != null) {
                val data = LocalAudioRepository.fromContext(appContext).loadAudio(localFile)
                    ?: error("Local audio not found.")
                player.setDataSource(ByteArrayAudioDataSource(data))
            } else {
                player.setDataSource(url)
            }
            player.prepareAsync()
            player.setOnPreparedListener { it.start() }
            mediaPlayer = player
        }.onFailure {
            player.release()
            abandonFocus()
        }
    }

    fun stop() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        abandonFocus()
    }

    private fun requestFocus(mode: AudioPlaybackMode): Boolean {
        if (mode == AudioPlaybackMode.Mix) return true
        val gain = when (mode) {
            AudioPlaybackMode.Interrupt -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioPlaybackMode.Duck -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioPlaybackMode.Mix -> AudioManager.AUDIOFOCUS_NONE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, gain) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private class ByteArrayAudioDataSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val length = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, destinationOffset = offset, startIndex = position.toInt(), endIndex = position.toInt() + length)
            return length
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }

    companion object {
        @Volatile
        private var instance: WordAudioPlayer? = null

        fun get(context: Context): WordAudioPlayer =
            instance ?: synchronized(this) {
                instance ?: WordAudioPlayer(context).also { instance = it }
            }
    }
}
