# Template comparison harness

Renders **one real note** (異変, deck Default) through all three candidate template
bases, front + back, so the goal #4 redesign base can be chosen from data, not
descriptions.

## Open it

```sh
cd research/template-preview
python3 -m http.server 8731   # file:// is blocked by browsers for iframes
# then open http://localhost:8731/preview.html
```

Top bar toggles **dark mode** and **mobile width** (≈AnkiDroid). Open it on your
phone (same LAN: `http://<pc-ip>:8731/preview.html`) to judge the real review device.

`light-desktop.png` / `dark-mobile.png` are reference captures.

## What each column is

| Column | Source | Pitch system | Notes |
|---|---|---|---|
| jidoujisho Kinomoto | current template (extracted from `collection.anki2`) | **JRP** managed JS (goal #2 fills its data) | the status quo |
| Lapis | `donkuri/lapis@f4eb29b` | own JS (reads jidoujisho SVG / PitchPosition) | heavy JS + 26 KB CSS, full yomitan field schema |
| crop-theft | `Kuuuube/crop-theft@88865e6` | shows the `PitchPattern` SVG as-is | ~no JS; **no dark-mode CSS** (stays light) |

## How fields were mapped

`build.py` does the real migration work in miniature:

- **`jrp_to_anki()`** converts jidoujisho/JRP sentence markup
  (`{[異変|いへん];0}…`) → Anki furigana notation (` 異変[いへん]…`). Lapis and
  crop expect plain furigana, *not* JRP markup, so this conversion is mandatory to
  port off Kinomoto. (Note `五` ends up with no reading — that's the real
  bare-kanji-outside-units gap goal #2 fills.)
- Emulates Anki's `{{furigana:}}` / `{{kanji:}}` / `{{kana:}}` / `{{text:}}`
  filters and the `[sound:…]` → replay-button conversion.
- Maps Kinomoto fields onto each template's schema (see the dicts in `build.py`).

Regenerate after editing: `uv run python build.py`.

To compare a different note, repoint `/tmp/note_rich.json` (see `parse_collection.py`).
