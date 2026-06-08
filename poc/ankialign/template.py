"""Apply the goal-#4 styling and the 'plain sentence when no cloze' front fix
to the Kinomoto note type. Idempotent (guarded by marker comments)."""
import os
from . import config

_CSS_MARKER = "/* ---- AnkiAlign custom"
_FRONT_MARKER = "ankialign-front-strip"

# Client-side strip: when there's no cloze, show the sentence as surface text
# (remove jidoujisho/JRP markup) instead of raw {..}.
_FRONT_SCRIPT = """
<script id="ankialign-front-strip">
(function(){
  if (`{{Cloze Inside}}`.trim()) return;          // cloze present -> leave as-is
  var el = document.getElementById("sentence");
  if (!el) return;
  var t = el.textContent;
  t = t.replace(/\\{([^{}]*)\\}/g, function(_, u){ return u.replace(/;[^;]*$/, ""); });
  t = t.replace(/\\[([^\\[\\]|=]*)=([^\\[\\]]*)\\]/g, "$1");   // [surface=base]
  t = t.replace(/\\[([^\\[\\]|]+)\\|([^\\[\\]]+)\\]/g, "$1");  // [漢字|かな] -> 漢字
  el.textContent = t;
})();
</script>"""


def _css() -> str:
    path = os.path.join(os.path.dirname(__file__), "kinomoto.css")
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# A previous version gave flat (heiban) 用言 a distinct "jrp-heiban-yougen" (teal)
# class. It wasn't worth the extra color, so flat verbs render as plain heiban again.
# Reverse the old patch on apply so existing installs self-heal (idempotent).
_RENDER_OLD = 'if (acc === 0) { return "jrp-heiban"; }'
_RENDER_NEW = ('if (acc === 0) { return is_yougen ? "jrp-heiban-yougen"'
               ' : "jrp-heiban"; }')

# Render the hover pitch graph in hiragana. The JRP managed script splits the
# graph's moras with split_moras(reading), which defaults to katakana; the
# mora-count calls (split_moras(reading).length) are script-agnostic and left
# alone. Re-applied each run so a JRP add-on regeneration self-heals.
_GRAPH_KATA = "split_moras(reading);"
_GRAPH_HIRA = "split_moras(reading, true);"


def apply(col) -> None:
    m = col.models.by_name(config.TARGET_NOTETYPE)
    if m is None:
        raise RuntimeError("target notetype missing: " + config.TARGET_NOTETYPE)
    # Replace (don't skip on marker presence) so an updated kinomoto.css always
    # lands. split()[0] keeps the original Kinomoto CSS; the old AnkiAlign block
    # (and any dict styles appended after it) is dropped — enrich re-appends the
    # dict styles right after this.
    base = m["css"].split(_CSS_MARKER)[0].rstrip()
    m["css"] = base + "\n\n" + _css()
    tmpl = m["tmpls"][0]
    if _FRONT_MARKER not in tmpl["qfmt"]:
        tmpl["qfmt"] = tmpl["qfmt"].rstrip() + "\n" + _FRONT_SCRIPT
    # undo the old heiban-用言 color patch if a prior version applied it
    if _RENDER_NEW in tmpl["afmt"]:
        tmpl["afmt"] = tmpl["afmt"].replace(_RENDER_NEW, _RENDER_OLD)
    # hover pitch graph: katakana -> hiragana (both front and back scripts)
    for key in ("qfmt", "afmt"):
        if _GRAPH_KATA in tmpl[key]:
            tmpl[key] = tmpl[key].replace(_GRAPH_KATA, _GRAPH_HIRA)
    col.models.update_dict(m)
