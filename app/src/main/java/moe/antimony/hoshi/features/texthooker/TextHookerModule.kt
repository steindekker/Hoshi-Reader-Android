package moe.antimony.hoshi.features.texthooker

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import moe.antimony.hoshi.features.anki.AnkiScreenshotSource
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal object TextHookerProvidesModule {
    @Provides
    @Singleton
    fun provideTextHookerSettingsRepository(@ApplicationContext context: Context): TextHookerSettingsRepository =
        context.textHookerSettingsRepository()

    /**
     * Short timeouts keep a sleeping or unreachable Deck from stalling mining; the client pings
     * the socket so a silently dropped Tailscale path is detected and reconnected.
     */
    @Provides
    @Singleton
    @TextHookerHttpClient
    fun provideTextHookerHttpClient(): OkHttpClient =
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
internal interface TextHookerBindingsModule {
    @Binds
    @Singleton
    fun bindTextHookerTransport(transport: OkHttpTextHookerTransport): TextHookerTransport

    @Binds
    fun bindAnkiScreenshotSource(repository: TextHookerRepository): AnkiScreenshotSource
}
