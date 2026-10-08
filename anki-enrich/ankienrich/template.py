"""Apply the repo-owned Kinomoto card design to the note type.

The canonical front/back/style live in card/kinomoto/ (first-class repo files).
apply() writes them onto the note type verbatim, then appends the generated
scoped dictionary styles (var/dict_styles.css) after a marker so a def-index
rebuild can refresh them without disturbing the authored design."""
from pathlib import Path

from . import config, paths


def _read(name: str) -> str:
    return Path(paths.CARD_DIR, name).read_text(encoding="utf-8")


def apply(col) -> None:
    m = col.models.by_name(config.NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.NOTETYPE)
    # back.html references {{Examples}}; add the field first so Anki's template
    # validation doesn't reject the design when examples enrichment hasn't run yet.
    if config.EXAMPLES not in {f["name"] for f in m["flds"]}:
        col.models.add_field(m, col.models.new_field(config.EXAMPLES))
    base_css = _read("style.css").rstrip()
    dict_css = ""
    dict_styles = Path(paths.DICT_STYLES)
    if dict_styles.exists():
        dict_css = "\n\n" + config.DICT_STYLES_MARKER + "\n" + dict_styles.read_text(encoding="utf-8")
    m["css"] = base_css + dict_css
    tmpl = m["tmpls"][0]
    tmpl["qfmt"] = _read("front.html")
    tmpl["afmt"] = _read("back.html")
    col.models.update_dict(m)
