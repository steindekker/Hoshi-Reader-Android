package moe.antimony.hoshi.features.reader

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** One-time mining hints. */
internal interface MiningTipRepository {
    /** True exactly once: the first time the user opens "Add to Anki" with a tap. */
    suspend fun consumeLongPressTip(): Boolean

    companion object {
        val None = object : MiningTipRepository {
            override suspend fun consumeLongPressTip() = false
        }
    }
}

private val Context.miningTipsDataStore by preferencesDataStore(name = "mining_tips")
private val LongPressTipShownKey = booleanPreferencesKey("long_press_tip_shown")

@Singleton
internal class DataStoreMiningTipRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : MiningTipRepository {
    override suspend fun consumeLongPressTip(): Boolean {
        var show = false
        context.miningTipsDataStore.edit { preferences ->
            if (preferences[LongPressTipShownKey] != true) {
                preferences[LongPressTipShownKey] = true
                show = true
            }
        }
        return show
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface MiningTipModule {
    @Binds
    fun bindMiningTipRepository(repository: DataStoreMiningTipRepository): MiningTipRepository
}
