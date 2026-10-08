package moe.antimony.hoshi.features.anki

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A note was added to Anki; [term] is the mined expression (blank when unknown). */
data class AnkiCardAdded(val term: String)

/** App-wide stream of added cards, published by [AnkiRepository] for every mining path. */
@Singleton
class AnkiCardAddedEvents @Inject constructor() {
    private val events = MutableSharedFlow<AnkiCardAdded>(extraBufferCapacity = 8)

    val added: SharedFlow<AnkiCardAdded> = events.asSharedFlow()

    fun publish(event: AnkiCardAdded) {
        events.tryEmit(event)
    }
}
