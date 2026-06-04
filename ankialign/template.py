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
    col.models.update_dict(m)
