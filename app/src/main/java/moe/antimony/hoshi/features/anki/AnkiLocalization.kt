package moe.antimony.hoshi.features.anki

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

@get:StringRes
internal val AnkiDuplicateScope.labelRes: Int
    get() = when (this) {
        AnkiDuplicateScope.Collection -> R.string.anki_duplicate_scope_collection
        AnkiDuplicateScope.Deck -> R.string.anki_duplicate_scope_deck
        AnkiDuplicateScope.DeckRoot -> R.string.anki_duplicate_scope_deck_root
    }
