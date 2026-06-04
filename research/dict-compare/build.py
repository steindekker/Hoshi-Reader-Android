#!/usr/bin/env python3
"""Dictionary comparison harness.

Top = word selector. Left = the real card (new AnkiAlign design, live notetype +
real note contents). Right = the same word's definition looked up in every
candidate dictionary, rendered from its Yomitan structured content with the
dict's own styles.css — so readability/coverage can be judged side by side.

Run:  cd research/dict-compare && uv run --with anki python build.py
Then: python3 -m http.server 8732   ->  http://localhost:8732/compare.html
"""
import json, re, html, glob, os
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
MEDIA_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.media")
COL_COPY = "/tmp/dc_col.anki2"

# id, folder, label, kind-badge  (order = recommended base chain first, J-E last)
DICT_ORDER = [
    ("sankoku8",  "sankoku8",  "三省堂国語辞典 第八版", "J-J · readable"),
    ("daijisen",  "daijisen",  "大辞泉 第二版",          "J-J · coverage"),
    ("daijirin4", "daijirin4", "大辞林 第四版",          "J-J"),
    ("smk8",      "smk8",      "新明解国語辞典 第八版",  "J-J"),
    ("koujien7",  "koujien7",  "広辞苑 第七版",          "J-J"),
    ("jitsuyo",   "jitsuyo",   "実用日本語表現辞典",     "J-J"),
    ("jitendex",  "jitendex",  "Jitendex",               "J-E"),
    ("jmdict",    "jmdict",    "JMdict",                 "J-E"),
]

# ───────────────────────────────────────────── jrp markup -> anki furigana ──
def jrp_to_anki(s: str) -> str:
    def unit(m):
        return re.sub(r";[^;]*$", "", m.group(1))
    s = re.sub(r"\{([^{}]*)\}", unit, s)
    s = re.sub(r"\[([^\[\]|=]*)=([^\[\]]*)\]", r"\1", s)
    s = re.sub(r"\[([^\[\]|]+)\|([^\[\]]+)\]", r" \1[\2]", s)
    return s.strip()

RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
def f_furigana(s): return RUBY.sub(r"<ruby>\1<rt>\2</rt></ruby>", s).replace("  ", " ").strip()
def f_kanji(s):    return RUBY.sub(r"\1", s).replace(" ", "")
def f_kana(s):     return RUBY.sub(r"\2", s).replace(" ", "")
def f_text(s):     return re.sub(r"<[^>]+>", "", RUBY.sub(r"\1", s))

SOUND = re.compile(r"\[sound:([^\]]+)\]")
PLAY_SVG = ('<svg viewBox="0 0 24 24" width="1em" height="1em"><circle cx="12" cy="12" r="11" '
            'fill="#3b82f6"/><path d="M9 7v10l8-5z" fill="#fff"/></svg>')
def shim_sound(s):
    return SOUND.sub(lambda m: f'<a class="replay-button" style="cursor:pointer" '
                               f'onclick="new Audio(\'media/{m.group(1)}\').play()">{PLAY_SVG}</a>', s)
def fix_media(s):
    s = re.sub(r'src="([^"]+\.(?:jpe?g|png|gif|webp))"', r'src="media/\1"', s, flags=re.I)
    s = re.sub(r'url\(("?)_', r'url(\1media/_', s)
    return s

# ───────────────────────────────────────────── mini-mustache (one note) ─────
def render(tmpl, fields):
    def truthy(name): return bool(str(fields.get(name, "")).strip())
    sec = re.compile(r"\{\{([#^])([^}]+)\}\}(.*?)\{\{/\2\}\}", re.S)
    prev = None
    while prev != tmpl:
        prev = tmpl
        def s(m):
            kind, name, body = m.group(1), m.group(2).strip(), m.group(3)
            show = truthy(name) if kind == "#" else not truthy(name)
            return body if show else ""
        tmpl = sec.sub(s, tmpl)
    def fil(m):
        flt, name = m.group(1), m.group(2).strip()
        raw = str(fields.get(name, ""))
        return {"furigana": f_furigana, "kanji": f_kanji, "kana": f_kana,
                "text": f_text}.get(flt, lambda x: x)(raw)
    tmpl = re.sub(r"\{\{(furigana|kanji|kana|text):([^}]+)\}\}", fil, tmpl)
    tmpl = re.sub(r"\{\{([^}#^/]+)\}\}", lambda m: str(fields.get(m.group(1).strip(), "")), tmpl)
    return tmpl

# ───────────────────────────────────────────── yomitan structured content ──
VOID = {"br", "hr", "img"}
def _kebab(k): return re.sub(r"[A-Z]", lambda m: "-" + m.group(0).lower(), k)

def render_sc(node):
    if node is None:
        return ""
    if isinstance(node, str):
        return html.escape(node).replace("\n", "<br>")
    if isinstance(node, list):
        return "".join(render_sc(c) for c in node)
    if isinstance(node, dict):
        t = node.get("type")
        if t == "structured-content":
            return render_sc(node.get("content"))
        if t == "text":
            return html.escape(node.get("text", "")).replace("\n", "<br>")
        if t == "image":
            return '<span class="sc-img">[画像]</span>'
        tag = node.get("tag")
        if tag is None:
            return render_sc(node.get("content"))
        attrs = ""
        style = node.get("style")
        if isinstance(style, dict):
            decls = [f"{_kebab(k)}:{v}" for k, v in style.items()]
            if decls:
                attrs += f' style="{html.escape(";".join(decls), quote=True)}"'
        data = node.get("data")
        if isinstance(data, dict):
            for k, v in data.items():
                attrs += f' data-{_kebab(k)}="{html.escape(str(v), quote=True)}"'
        if tag == "a" and node.get("href"):
            attrs += f' href="{html.escape(node["href"], quote=True)}"'
        if tag in VOID:
            return f"<{tag}{attrs}>"
        return f"<{tag}{attrs}>{render_sc(node.get('content'))}</{tag}>"
    return ""

def render_defs(defs):
    return "".join(render_sc(d) for d in defs)

def scope_css(css, sel):
    """Naively prefix every selector list with `sel ` so dicts don't clash."""
    def repl(m):
        sels = m.group(1)
        if sels.strip().startswith("@"):
            return m.group(0)
        parts = [f"{sel} {s.strip()}" for s in sels.split(",") if s.strip()]
        return ", ".join(parts) + "{"
    return re.sub(r"([^{}]+)\{", repl, css)

# ───────────────────────────────────────────── frequency (jpdb) ─────────────
def load_freq():
    freq = {}  # term -> rank (min)
    for fp in sorted(glob.glob(str(DICTS / "jpdb" / "term_meta_bank_*.json"))):
        for e in json.load(open(fp, encoding="utf-8")):
            term, _, meta = e[0], e[1], e[2]
            v = meta
            if isinstance(v, dict):
                if "frequency" in v:
                    v = v["frequency"]
                rank = v.get("value") if isinstance(v, dict) else v
            else:
                rank = v
            if isinstance(rank, int):
                if term not in freq or rank < freq[term]:
                    freq[term] = rank
    return freq

# log-spaced frequency bands (jpdb rank). Last band = rarer / unranked.
BANDS = [
    (1, 150,        "top 150"),
    (150, 500,      "150–500"),
    (500, 1200,     "500–1.2k"),
    (1200, 3000,    "1.2k–3k"),
    (3000, 7000,    "3k–7k"),
    (7000, 15000,   "7k–15k"),
    (15000, 30000,  "15k–30k"),
    (30000, 60000,  "30k–60k"),
    (60000, 200000, "60k–200k"),
    (200000, 10**9, "200k+ / rare"),
]

PER_BAND = 3  # representative words per frequency band

def pick_words(notes, freq):
    """A few representative words per band: prefers clean kanji vocab with a Meaning.
    Within a band, spreads picks across the rank range (low / mid / high) so they
    aren't all clustered at one end."""
    def score(n):  # higher = better representative
        term = n["Term"]
        has_kanji = bool(re.search(r"[㐀-鿿々]", term))
        return (has_kanji, bool(n["Meaning"].strip()), len(term) >= 2)
    chosen = []
    used = set()
    for lo, hi, label in BANDS:
        cands = [n for n in notes
                 if lo <= n["rank"] < hi and n["Term"] not in used]
        if not cands:
            continue
        # keep only good representatives, then sample evenly across the rank span
        cands = [n for n in cands if score(n) == (True, True, True)] or cands
        cands.sort(key=lambda n: n["rank"])
        k = min(PER_BAND, len(cands))
        idxs = [round(j * (len(cands) - 1) / max(k - 1, 1)) for j in range(k)]
        for idx in dict.fromkeys(idxs):
            pick = cands[idx]
            used.add(pick["Term"])
            pick["band"] = label
            chosen.append(pick)
    return chosen

# ───────────────────────────────────────────── dict lookup ──────────────────
def collect_matches(folder, terms, readings):
    """All entries whose expression or reading is one of our target words."""
    out = []  # (expr, reading, html)
    for tb in sorted(glob.glob(str(DICTS / folder / "term_bank_*.json"))):
        for e in json.load(open(tb, encoding="utf-8")):
            expr, read = e[0], e[1]
            if expr in terms or expr in readings or (read and read in readings):
                out.append((expr, read, render_defs(e[5])))
    return out

_KANJI = re.compile(r"[㐀-鿿々]")
def defs_for(matches, term, reading):
    """Pick the entries belonging to (term, reading) and join them.

    Match on the written form first. Only fall back to a reading match when the
    term itself is kana — otherwise a kanji word with no headword entry would
    drag in every homophone (e.g. 負い → all おい words), not show '— no entry —'.
    """
    exact = [h for (e, r, h) in matches if e == term and (r == reading or r == "" or reading == "")]
    if exact:
        return exact
    by_term = [h for (e, r, h) in matches if e == term]
    if by_term:
        return by_term
    if not _KANJI.search(term):  # kana headword (term written in kana)
        return [h for (e, r, h) in matches if e == reading or r == reading]
    return []

# ───────────────────────────────────────────── build ────────────────────────
def main():
    import shutil
    shutil.copy(COL_SRC, COL_COPY)
    col = Collection(COL_COPY)
    m = col.models.by_name("jidoujisho Kinomoto")
    kino_css = fix_media(m["css"])
    kino_front_t = m["tmpls"][0]["qfmt"]
    kino_back_t = m["tmpls"][0]["afmt"]
    fld_names = [f["name"] for f in m["flds"]]

    freq = load_freq()
    nids = col.find_notes('note:"jidoujisho Kinomoto"')
    notes = []
    for nid in nids:
        n = col.get_note(nid)
        d = dict(zip(fld_names, n.fields))
        term = d.get("Term", "").strip()
        if not term:
            continue
        d["rank"] = freq.get(term) or freq.get(d.get("Reading", "").strip()) or 10**9
        notes.append(d)
    col.close()

    chosen = pick_words(notes, freq)
    print(f"selected {len(chosen)} words:",
          ", ".join(f'{n["Term"]}({n["rank"] if n["rank"]<10**9 else "—"})' for n in chosen))

    terms = {n["Term"] for n in chosen}
    readings = {n["Reading"].strip() for n in chosen if n["Reading"].strip()}

    # one pass per dict, keep only entries matching our words
    matches = {}
    for did, folder, *_ in DICT_ORDER:
        matches[did] = collect_matches(folder, terms, readings)
        print(f"  {did}: {len(matches[did])} matching entries")

    # copy media for the chosen notes (images + audio)
    Path(ROOT / "media").mkdir(exist_ok=True)
    for n in chosen:
        for fld in ("Image", "Term Audio", "Sentence Audio"):
            for fn in re.findall(r'src="([^"]+)"|\[sound:([^\]]+)\]|^([^<\[][^"]*\.(?:mp3|jpg|jpeg|png|webp))$',
                                  n.get(fld, ""), re.I):
                name = next((x for x in fn if x), "")
                src = Path(MEDIA_SRC) / name
                if name and src.exists():
                    shutil.copy(src, ROOT / "media" / name)

    # ---- assemble per-word rows -------------------------------------------
    rows = ""
    options = ""
    for i, n in enumerate(chosen):
        term, reading = n["Term"], n["Reading"].strip()
        rank_lbl = str(n["rank"]) if n["rank"] < 10**9 else "—"

        # left: real card (back side = full design with the definition)
        sent_anki = jrp_to_anki(n.get("Sentence", ""))
        kfields = {k: shim_sound(fix_media(v)) for k, v in n.items() if isinstance(v, str)}
        kfields["SentenceFurigana"] = f_furigana(sent_anki)
        back_html = render(kino_back_t, kfields)
        # auto-size the iframe to its content, and forward wheel events to the
        # parent page so scrolling over the card doesn't get trapped in the frame.
        card_js = ("<script>function _fit(){try{frameElement.style.height="
                   "Math.max(document.body.scrollHeight,300)+'px';}catch(e){}}"
                   "addEventListener('load',()=>setTimeout(_fit,80));"
                   "addEventListener('wheel',e=>{try{parent.scrollBy(0,e.deltaY);}"
                   "catch(_){}}, {passive:true});</script>")
        card_doc = (f"<!doctype html><html><head><meta charset=utf-8>"
                    f"<style>html,body{{margin:0}}body{{padding:10px}}\n{kino_css}\n</style>"
                    f"{card_js}</head><body class=card>{back_html}</body></html>")

        # right: dict panels
        panels = ""
        for did, folder, label, kind in DICT_ORDER:
            blocks = defs_for(matches[did], term, reading)
            if blocks:
                body = '<div class="sep"></div>'.join(blocks)
            else:
                body = '<div class="noentry">— no entry —</div>'
            jj = "jj" if kind.startswith("J-J") else "je"
            panels += (f'<div class="panel">'
                       f'<div class="phead {jj}"><span class="dname">{html.escape(label)}</span>'
                       f'<span class="badge">{html.escape(kind)}</span></div>'
                       f'<div class="pbody dict-{did}">{body}</div></div>')

        disp = "" if i == 0 else "display:none"
        rows += (f'<div class="row" data-i="{i}" style="{disp}">'
                 f'<div class="left"><div class="lhead">card · 新design</div>'
                 f'<iframe srcdoc="{html.escape(card_doc, quote=True)}"></iframe></div>'
                 f'<div class="right">{panels}</div></div>')
        options += (f'<option value="{i}">{html.escape(term)}'
                    f'{"・"+html.escape(reading) if reading else ""}  ·  freq {rank_lbl} ({n["band"]})</option>')

    # scoped dict stylesheets (only daijisen + jitendex ship one)
    dict_styles = ""
    for did, folder, *_ in DICT_ORDER:
        sp = DICTS / folder / "styles.css"
        if sp.exists():
            dict_styles += "\n" + scope_css(sp.read_text(encoding="utf-8"), f".dict-{did}")

    page = PAGE.replace("__OPTIONS__", options).replace("__ROWS__", rows)\
               .replace("__DICTCSS__", dict_styles)
    (ROOT / "compare.html").write_text(page, encoding="utf-8")
    print("wrote", ROOT / "compare.html", f"({len(page)//1024} KB)")


PAGE = r"""<!doctype html><html lang=ja><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>AnkiAlign · dictionary comparison</title>
<style>
  :root{--bg:#e9e9ec;--fg:#222;--panel:#fff;--line:#d6d6db;--head:#1f2430;--muted:#777}
  body.dark{--bg:#191b20;--fg:#e6e3dc;--panel:#23262d;--line:#33363d;--muted:#9a9a9a}
  *{box-sizing:border-box}
  body{font-family:system-ui,sans-serif;margin:0;background:var(--bg);color:var(--fg)}
  .bar{position:sticky;top:0;z-index:10;background:var(--head);color:#eee;
       padding:10px 16px;display:flex;gap:16px;align-items:center;flex-wrap:wrap}
  .bar b{font-size:15px}
  select{font-size:14px;padding:5px 8px;border-radius:6px;border:1px solid #555;
         background:#fff;color:#222;max-width:62vw}
  .bar label{font-size:13px;cursor:pointer}
  .note{font-size:12px;opacity:.7}
  .row{display:flex;gap:16px;padding:16px;align-items:flex-start}
  .left{flex:0 0 var(--cw,420px);max-width:var(--cw,420px);position:sticky;top:64px}
  .lhead,.lhead{font-size:11px;letter-spacing:.08em;color:var(--muted);margin:0 0 4px 2px}
  .left iframe{width:100%;height:560px;border:1px solid var(--line);border-radius:10px;background:#fff}
  .right{flex:1;display:flex;flex-direction:column;gap:12px;min-width:0}
  .panel{background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden}
  .phead{display:flex;align-items:center;justify-content:space-between;gap:8px;
         padding:8px 12px;border-bottom:1px solid var(--line)}
  .phead.jj{border-left:4px solid #5566b5}
  .phead.je{border-left:4px solid #b58a55}
  .dname{font-weight:600;font-size:14px}
  .badge{font-size:11px;color:var(--muted);white-space:nowrap}
  .pbody{padding:10px 14px;font-family:"Hiragino Mincho ProN","Noto Serif CJK JP","Noto Serif JP",serif;
         font-size:15px;line-height:1.7;word-break:break-word}
  .pbody ul,.pbody ol{margin:.3em 0;padding-left:1.4em}
  .pbody li{margin:.15em 0}
  .pbody rt{font-size:.7em;opacity:.8}
  .pbody a{color:inherit;text-decoration:none}
  .sep{height:1px;background:var(--line);margin:8px 0}
  .noentry{color:var(--muted);font-style:italic;font-size:13px}
  .sc-img{color:var(--muted);font-size:12px}
  @media(max-width:820px){
    .row{flex-direction:column}
    .left{position:static;flex-basis:auto;max-width:100%;width:100%}
  }
  /* ---- scoped dictionary stylesheets ---- */
  __DICTCSS__
</style></head><body>
<div class="bar">
  <b>AnkiAlign · dict comparison</b>
  <select id="word">__OPTIONS__</select>
  <label><input type="checkbox" id="dark"> dark</label>
  <span class="note">left = real card (new design) · right = same word in every dictionary</span>
</div>
__ROWS__
<script>
const sel=document.getElementById('word'), dark=document.getElementById('dark');
function showRow(){
  const i=sel.value;
  for(const r of document.querySelectorAll('.row')) r.style.display = (r.dataset.i===i)?'flex':'none';
  syncCards();
}
function syncCards(){
  const d=dark.checked;
  for(const f of document.querySelectorAll('.row[style*="flex"] iframe, .row:not([style*="none"]) iframe')){
    const run=()=>{try{const doc=f.contentDocument;if(!doc)return;
      doc.body.className='card'+(d?' nightMode night_mode':'');}catch(e){}};
    f.onload=run; run();
  }
}
sel.onchange=showRow;
dark.onchange=()=>{document.body.classList.toggle('dark',dark.checked);syncCards();};
window.addEventListener('load',()=>setTimeout(showRow,150));
</script></body></html>"""

if __name__ == "__main__":
    main()
