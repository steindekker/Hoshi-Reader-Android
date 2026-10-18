package moe.antimony.hoshi.features.anki

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import java.lang.SecurityException
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

class AndroidAnkiContentApi @Inject constructor(
    @ApplicationContext
    context: Context,
) : AnkiContentApi {
    private val appContext = context.applicationContext
    private val api = AddContentApi(appContext)

    override fun deckList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.DeckListUnavailable) {
            api.deckList ?: throw AnkiFetchException(AnkiFetchFailure.DeckListUnavailable)
        }

    override fun modelList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.ModelListUnavailable) {
            api.modelList ?: throw AnkiFetchException(AnkiFetchFailure.ModelListUnavailable)
        }

    override fun fieldList(modelId: Long): List<String> =
        readAnkiApi(AnkiFetchFailure.ModelFieldsUnavailable) {
            api.getFieldList(modelId)?.toList()
                ?: throw AnkiFetchException(AnkiFetchFailure.ModelFieldsUnavailable)
        }

    override fun findDuplicateNotes(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Boolean = findNoteId(
        deck = deck,
        modelId = modelId,
        key = key,
        duplicateScope = duplicateScope,
        checkAllModels = checkAllModels,
    ) != null

    override fun findNoteId(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Long? {
        if (key.isBlank()) return null
        val checksum = ankiFirstFieldChecksum(key)
        if (checksum == 0L) return null
        val scopeDeckIds = ankiDuplicateScopeDeckIds(
            decksById = deckList(),
            selectedDeck = deck,
            duplicateScope = duplicateScope,
        )
        val cursor = appContext.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI_V2,
            NoteProjection,
            ankiDuplicateNoteSelection(
                modelId = modelId,
                checksum = checksum,
                checkAllModels = checkAllModels,
            ),
            null,
            null,
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val noteId = it.getLong(it.getColumnIndexOrThrow(FlashCardsContract.Note._ID))
                if (duplicateScope == AnkiDuplicateScope.Collection || noteHasCardInDeck(noteId, scopeDeckIds)) {
                    return noteId
                }
            }
        }
        return null
    }

    override fun addNote(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        tags: Set<String>,
    ): Long? = api.addNote(modelId, deckId, fields, tags)

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(
            Uri.parse(uriString),
            ankiPreferredMediaName(preferredName),
            if (mimeType.startsWith("audio/")) "audio" else "image",
        )

    override fun sync(): Boolean {
        appContext.startActivity(ankiDroidSyncIntent())
        return true
    }

    override fun openNotes(query: String): Boolean {
        val spec = ankiDroidBrowserIntentSpec(
            query = query,
            packageName = AddContentApi.getAnkiDroidPackageName(appContext) ?: "com.ichi2.anki",
        )
        val intent = Intent(spec.action, Uri.parse(spec.uri)).setPackage(spec.packageName)
        // From the visible Hoshi activity, the browser opens on top of Hoshi and Back
        // returns here; only without one does it need a task of its own.
        val activity = ResumedActivityTracker.current()
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun isAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(appContext) != null

    override fun addModel(spec: AnkiNoteTypeSpec, deckId: Long?): Long? =
        readAnkiApi(AnkiFetchFailure.ProviderFailure) {
            api.addNewCustomModel(
                spec.name,
                spec.fields.toTypedArray(),
                arrayOf(spec.cardName),
                arrayOf(spec.front),
                arrayOf(spec.back),
                spec.css,
                deckId,
                0,
            )
        }

    private inline fun <T> readAnkiApi(fallbackFailure: AnkiFetchFailure, block: () -> T): T {
        if (!isAvailable()) throw AnkiFetchException(AnkiFetchFailure.ApiUnavailable)
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AnkiFetchException) {
            throw error
        } catch (error: SecurityException) {
            logAnkiApiFailure("AnkiDroid database permission denied.", error)
            throw AnkiFetchException(AnkiFetchFailure.PermissionDenied, cause = error)
        } catch (error: Throwable) {
            logAnkiApiFailure("AnkiDroid API request failed.", error)
            throw AnkiFetchException(fallbackFailure, cause = error)
        }
    }

    private fun noteHasCardInDeck(noteId: Long, deckIds: Set<Long>): Boolean {
        if (deckIds.isEmpty()) return false
        val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        val cardUri = Uri.withAppendedPath(noteUri, "cards")
        val cursor = appContext.contentResolver.query(
            cardUri,
            CardProjection,
            null,
            null,
            null,
        ) ?: return false

        cursor.use {
            while (it.moveToNext()) {
                val cardDeckId = it.getLong(it.getColumnIndexOrThrow(FlashCardsContract.Card.DECK_ID))
                if (cardDeckId in deckIds) return true
            }
        }
        return false
    }

    private companion object {
        val NoteProjection = arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.CSUM)
        val CardProjection = arrayOf(FlashCardsContract.Card.DECK_ID)
    }
}

private fun logAnkiApiFailure(message: String, error: Throwable) {
    runCatching { Log.w("AndroidAnkiContentApi", message, error) }
}

internal fun ankiDroidSyncIntent(): Intent =
    ankiDroidSyncIntentSpec().let { spec ->
        Intent(spec.action).apply {
            setPackage(spec.packageName)
            spec.categories.forEach(::addCategory)
            flags = spec.flags
        }
    }

internal data class AnkiDroidSyncIntentSpec(
    val action: String,
    val packageName: String,
    val categories: Set<String>,
    val flags: Int,
)

internal fun ankiDroidSyncIntentSpec(): AnkiDroidSyncIntentSpec =
    AnkiDroidSyncIntentSpec(
        action = "com.ichi2.anki.DO_SYNC",
        packageName = "com.ichi2.anki",
        categories = setOf(Intent.CATEGORY_DEFAULT),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_HISTORY or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION,
    )

internal data class AnkiDroidBrowserIntentSpec(
    val action: String,
    val uri: String,
    val packageName: String,
)

/**
 * AnkiDroid's public browser deep link. `CardBrowser` itself is not exported (2.25 refuses an
 * explicit start with a SecurityException), so the search goes through `IntentHandler`'s
 * `anki://x-callback-url/browser` filter. The query keeps the requested duplicate scope.
 */
internal fun ankiDroidBrowserIntentSpec(
    query: String,
    packageName: String = "com.ichi2.anki",
): AnkiDroidBrowserIntentSpec =
    AnkiDroidBrowserIntentSpec(
        action = Intent.ACTION_VIEW,
        uri = "anki://x-callback-url/browser?search=" + percentEncode(query),
        packageName = packageName,
    )

private fun percentEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

internal fun ankiDuplicateNoteSelection(
    modelId: Long,
    checksum: Long,
    checkAllModels: Boolean,
): String {
    val checksumSelection = String.format(
        Locale.US,
        "%s in (%d)",
        FlashCardsContract.Note.CSUM,
        checksum,
    )
    return if (checkAllModels) {
        checksumSelection
    } else {
        String.format(
            Locale.US,
            "%s=%d and %s",
            FlashCardsContract.Note.MID,
            modelId,
            checksumSelection,
        )
    }
}

internal fun ankiPreferredMediaName(preferredName: String): String {
    val fileName = preferredName.substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        .ifBlank { "hoshi_media" }
}

internal fun ankiDuplicateScopeDeckIds(
    decksById: Map<Long, String>,
    selectedDeck: AnkiDeck,
    duplicateScope: AnkiDuplicateScope,
): Set<Long> =
    when (duplicateScope) {
        AnkiDuplicateScope.Collection -> emptySet()
        AnkiDuplicateScope.Deck -> setOf(selectedDeck.id)
        AnkiDuplicateScope.DeckRoot -> {
            val rootName = selectedDeck.name.substringBefore("::")
            decksById.mapNotNull { (id, name) ->
                id.takeIf { name == rootName || name.startsWith("$rootName::") }
            }.toSet()
        }
    }

internal fun ankiFirstFieldChecksum(data: String): Long {
    // Anki normalizes note fields to NFC before stripping HTML and computing csum.
    val strippedData = Normalizer.normalize(data, Normalizer.Form.NFC).stripHtmlMedia()
    val digest = MessageDigest.getInstance("SHA1")
        .digest(strippedData.toByteArray(StandardCharsets.UTF_8))
    val hex = BigInteger(1, digest).toString(16).padStart(40, '0')
    return hex.substring(0, 8).toLong(16)
}

private val StyleRegex = Regex("(?is)<style.*?>.*?</style>")
private val ScriptRegex = Regex("(?is)<script.*?>.*?</script>")
private val TagRegex = Regex("<.*?>")
private val ImgRegex = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>", RegexOption.IGNORE_CASE)
private val HtmlEntityRegex = Regex("&#?\\w+;")

private fun String.stripHtmlMedia(): String =
    ImgRegex.replace(this) { " ${it.groupValues[1]} " }
        .replace(StyleRegex, "")
        .replace(ScriptRegex, "")
        .replace(TagRegex, "")
        .decodeHtmlEntities()

private fun String.decodeHtmlEntities(): String =
    HtmlEntityRegex.replace(replace("&nbsp;", " ")) { match ->
        when (val entity = match.value) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&#39;", "&apos;" -> "'"
            else -> entity.decodeNumericHtmlEntity() ?: entity
        }
    }

private fun String.decodeNumericHtmlEntity(): String? {
    val value = removePrefix("&#").removeSuffix(";")
    val codePoint = when {
        value.startsWith("x", ignoreCase = true) -> value.drop(1).toIntOrNull(16)
        value.all(Char::isDigit) -> value.toIntOrNull()
        else -> null
    } ?: return null
    return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
}

/**
 * The app's currently resumed activity, so external screens (AnkiDroid's browser) open on top
 * of it and Back returns to Hoshi. Installed from `Application.onCreate` so the first resume is
 * never missed.
 */
internal object ResumedActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumed: WeakReference<Activity>? = null
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    fun current(): Activity? = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
