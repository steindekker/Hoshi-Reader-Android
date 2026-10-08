"""Definition lookup for the pack (`definition_for`) and the notetype's
scoped dictionary stylesheet. The note writing lives in ankienrich.fill."""
import unicodedata
from pathlib import Path

from ankienrich import config, paths
from ankienrich.defs import index, render


def apply_dict_styles(col, styles_path=None) -> None:
    """Append the scoped dict stylesheet to the notetype CSS (marker-guarded)."""
    path = Path(styles_path or paths.DICT_STYLES)
    if not path.exists():
        return
    css = path.read_text(encoding="utf-8")
    m = col.models.by_name(config.NOTETYPE)
    base = m["css"].split(config.DICT_STYLES_MARKER, 1)[0]
    m["css"] = base.rstrip() + f"\n\n{config.DICT_STYLES_MARKER}\n" + css
    col.models.update_dict(m)


def _store_norm(text: str) -> str:
    """Mirror Anki's field normalization (NFC, no carriage returns) so a value
    we compute round-trips equal to what Anki actually stores. Without this the
    overwrite idempotency check never converges and re-asserts the same notes
    on every run."""
    return unicodedata.normalize("NFC", text).replace("\r", "")


def _compute(conn, term, reading):
    """Return (new_meaning, source, kind) for one note. kind in enrich/english/nohit."""
    jj_rows, je_rows = index.lookup(conn, term)
    jj = render.choose_jj(jj_rows, reading, config.JJ_CHAIN)
    je = render.numbered_glosses(je_rows, reading, config.GLOSS_CAP)
    meaning = _store_norm(render.assemble_meaning(jj, je))
    source = _store_norm(render.source_label(jj, bool(je), config.DICT_LABELS))
    kind = "enrich" if jj else ("english" if je else "nohit")
    return meaning, source, kind


def definition_for(conn, term, reading):
    """Public single-note definition lookup: (meaning, source, kind)."""
    return _compute(conn, term, reading)
