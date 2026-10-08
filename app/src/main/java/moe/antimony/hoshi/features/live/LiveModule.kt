package moe.antimony.hoshi.features.live

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import moe.antimony.hoshi.features.anki.AnkiLiveMediaSource
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal object LiveProvidesModule {
    @Provides
    @Singleton
    fun provideLiveSettingsRepository(@ApplicationContext context: Context): LiveSettingsRepository =
        context.liveSettingsRepository()

    /**
     * Short timeouts keep a sleeping or unreachable host from stalling the tab; the client pings
     * the sockets so a silently dropped Tailscale path is detected and reconnected.
     */
    @Provides
    @Singleton
    @LiveHttpClient
    fun provideLiveHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
internal interface LiveBindingsModule {
    @Binds
    @Singleton
    fun bindLiveTransport(transport: OkHttpLiveTransport): LiveTransport

    @Binds
    fun bindAnkiLiveMediaSource(repository: LiveRepository): AnkiLiveMediaSource
}
