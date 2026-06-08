"""Strip dead Yomitan ?query= cross-reference links from enriched definitions.

三省堂 (and other Yomitan-imported) defs carry relative anchors like
`<a href="?query=WORD&wildcards=off">WORD</a>`. In any Anki/AnkiDroid webview
these resolve to a dead `http://127.0.0.1:<port>/?query=…`. Unwrap them to the
inner text so the word becomes plain *selectable* text. Absolute links
(e.g. `https://maps.apple.com/…` geo-refs) are left clickable. Idempotent.
"""
import re

from . import config

FIELD = "Meaning"
_ANCHOR = re.compile(r"<a\b([^>]*)>(.*?)</a>", re.S)  # anchors don't nest
_HREF = re.compile(r'href="([^"]*)"')


def _unwrap(m: "re.Match") -> str:
    href_m = _HREF.search(m.group(1))
    href = href_m.group(1) if href_m else ""
    if "://" in href:            # absolute -> keep clickable
        return m.group(0)
    return m.group(2)            # relative/internal -> drop wrapper, keep text


def fix_field(val: str) -> str:
    return _ANCHOR.sub(_unwrap, val)


def _targets(col):
    if col.models.by_name(config.TARGET_NOTETYPE) is None:
        return []
    return list(col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'))


def run(col, *, apply: bool = False) -> dict:
    """Unwrap dead anchors in Meaning. Returns {'changed', 'anchors'}."""
    changed, anchors, batch = 0, 0, []
    for nid in _targets(col):
        note = col.get_note(nid)
        if FIELD not in note:
            continue
        val = note[FIELD]
        new = fix_field(val)
        if new != val:
            anchors += len(_ANCHOR.findall(val)) - len(_ANCHOR.findall(new))
            changed += 1
            if apply:
                note[FIELD] = new
                batch.append(note)
    if apply and batch:
        col.update_notes(batch)
    return {"changed": changed, "anchors": anchors}
