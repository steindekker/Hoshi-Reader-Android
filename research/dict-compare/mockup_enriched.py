#!/usr/bin/env python3
"""Mockup: how an ENRICHED card will look.

Renders real notes through the live Kinomoto template, Meaning replaced by the
assembled enrichment output: native J-J HTML (first hit in the chain
三省堂→広辞苑→実用→大辞泉) on top + JMdict numbered glosses (cap 4) below.

Now faithful to native styling: each dict's own styles.css is scoped in, ruby
furigana + list spacing polished, and per tier it surfaces the RICHEST example
(most furigana / size variation) so the styling is actually visible.

Run: uv run --with anki python mockup_enriched.py  -> mockup.html
"""
import json, glob, re, html, os, shutil
from anki.collection import Collection

DICTS = "dicts"
JJ_CHAIN = [("sankoku8", "三省堂"), ("koujien7", "広辞苑"), ("jitsuyo", "実用"), ("daijisen", "大辞泉")]

# ---- pure helpers (shared with build.py) ----
def jrp_to_anki(s):
    s = re.sub(r"\{([^{}]*)\}", lambda m: re.sub(r";[^;]*$", "", m.group(1)), s)
    s = re.sub(r"\[([^\[\]|=]*)=([^\[\]]*)\]", r"\1", s)
    s = re.sub(r"\[([^\[\]|]+)\|([^\[\]]+)\]", r" \1[\2]", s)
    return s.strip()
RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
def f_furigana(s): return RUBY.sub(r"<ruby>\1<rt>\2</rt></ruby>", s).replace("  ", " ").strip()
def f_kanji(s): return RUBY.sub(r"\1", s).replace(" ", "")
def f_kana(s): return RUBY.sub(r"\2", s).replace(" ", "")
def f_text(s): return re.sub(r"<[^>]+>", "", RUBY.sub(r"\1", s))
SOUND = re.compile(r"\[sound:([^\]]+)\]")
PLAY = ('<svg viewBox="0 0 24 24" width="1em" height="1em"><circle cx="12" cy="12" r="11" '
        'fill="#3b82f6"/><path d="M9 7v10l8-5z" fill="#fff"/></svg>')
def shim_sound(s): return SOUND.sub(lambda m: f'<a class="replay-button" style="cursor:pointer" onclick="new Audio(\'media/{m.group(1)}\').play()">{PLAY}</a>', s)
def fix_media(s):
    s = re.sub(r'src="([^"]+\.(?:jpe?g|png|gif|webp))"', r'src="media/\1"', s, flags=re.I)
    return re.sub(r'url\(("?)_', r'url(\1media/_', s)
def render(tmpl, fields):
    def truthy(n): return bool(str(fields.get(n, "")).strip())
    sec = re.compile(r"\{\{([#^])([^}]+)\}\}(.*?)\{\{/\2\}\}", re.S)
    prev = None
    while prev != tmpl:
        prev = tmpl
        tmpl = sec.sub(lambda m: m.group(3) if (truthy(m.group(2).strip()) == (m.group(1) == "#")) else "", tmpl)
    tmpl = re.sub(r"\{\{(furigana|kanji|kana|text):([^}]+)\}\}",
                  lambda m: {"furigana": f_furigana, "kanji": f_kanji, "kana": f_kana, "text": f_text}[m.group(1)](str(fields.get(m.group(2).strip(), ""))), tmpl)
    return re.sub(r"\{\{([^}#^/]+)\}\}", lambda m: str(fields.get(m.group(1).strip(), "")), tmpl)
VOID = {"br", "hr", "img"}
def _kebab(k): return re.sub(r"[A-Z]", lambda m: "-" + m.group(0).lower(), k)
def render_sc(n):
    if n is None: return ""
    if isinstance(n, str): return html.escape(n).replace("\n", "<br>")
    if isinstance(n, list): return "".join(render_sc(c) for c in n)
    if isinstance(n, dict):
        t = n.get("type")
        if t == "structured-content": return render_sc(n.get("content"))
        if t == "text": return html.escape(n.get("text", ""))
        if t == "image": return ""
        tag = n.get("tag")
        if tag is None: return render_sc(n.get("content"))
        attrs = ""
        st = n.get("style")
        if isinstance(st, dict):
            d = [f"{_kebab(k)}:{v}" for k, v in st.items()]
            if d: attrs += f' style="{html.escape(";".join(d), quote=True)}"'
        da = n.get("data")
        if isinstance(da, dict):
            for k, v in da.items(): attrs += f' data-{_kebab(k)}="{html.escape(str(v), quote=True)}"'
        if tag == "a" and n.get("href"): attrs += f' href="{html.escape(n["href"], quote=True)}"'
        if tag in VOID: return f"<{tag}{attrs}>"
        return f"<{tag}{attrs}>{render_sc(n.get('content'))}</{tag}>"
    return ""

def jmdict_glosses(entries):
    senses = []
    for e in sorted(entries, key=lambda e: -e[4]):
        out = []
        def walk(n):
            if isinstance(n, dict):
                if n.get("data", {}).get("content") == "glossary":
                    c = n.get("content")
                    for li in (c if isinstance(c, list) else [c]):
                        if isinstance(li, dict) and li.get("tag") == "li" and isinstance(li.get("content"), str):
                            out.append(li["content"])
                else:
                    walk(n.get("content"))
            elif isinstance(n, list):
                for x in n: walk(x)
        walk(e[5])
        if out: senses.append("; ".join(out))
    return "<br>".join(f"{i}. {s}" for i, s in enumerate(senses[:4], 1))

def load_sets(folder):
    ex, rd = set(), set()
    for tb in glob.glob(f"{DICTS}/{folder}/term_bank_*.json"):
        for e in json.load(open(tb, encoding="utf-8")):
            ex.add(e[0])
            if e[1]: rd.add(e[1])
    return ex, rd

def find_entries(folder, terms, readings):
    out = []
    for tb in glob.glob(f"{DICTS}/{folder}/term_bank_*.json"):
        for e in json.load(open(tb, encoding="utf-8")):
            if e[0] in terms or e[0] in readings or (e[1] and e[1] in readings):
                out.append(e)
    return out

def pick(entries, t, r):
    for e in entries:
        if e[0] == t and (e[1] == r or e[1] == "" or r == ""): return e
    for e in entries:
        if e[0] == t: return e
    for e in entries:
        if e[1] == r or e[0] == r: return e
    return None

def pick_all(entries, t, r):
    ex = [e for e in entries if e[0] == t and (e[1] == r or e[1] == "" or r == "")]
    if ex: return ex
    bt = [e for e in entries if e[0] == t]
    if bt: return bt
    return [e for e in entries if e[1] == r or e[0] == r]

def scope_css(css, sel):
    def repl(m):
        sels = m.group(1)
        if sels.strip().startswith("@"): return m.group(0)
        return ", ".join(f"{sel} {s.strip()}" for s in sels.split(",") if s.strip()) + "{"
    return re.sub(r"([^{}]+)\{", repl, css)

def richness(h):  # surface the most styled example per tier
    return h.count("<ruby") * 20 + h.count("font-size")


def main():
    sets = {f: load_sets(f) for f, _ in JJ_CHAIN}
    jm = load_sets("jmdict")
    def jjhit(f, t, r):
        ex, rd = sets[f]; return t in ex or (r and (r in ex or r in rd)) or t in rd
    def first_jj(t, r):
        for f, name in JJ_CHAIN:
            if jjhit(f, t, r): return f, name
        return None, None
    def jmhit(t, r):
        ex, rd = jm; return t in ex or (r and (r in ex or r in rd)) or t in rd

    shutil.copy(os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2"), "/tmp/dc_mock.anki2")
    col = Collection("/tmp/dc_mock.anki2")
    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    kcss = fix_media(m["css"]); kback = m["tmpls"][0]["afmt"]

    # collect many candidates per tier (notes with an image, so cards look real);
    # a wide pool lets the richness picker surface a real furigana(ruby) example.
    CANDS = 60
    cand = {"sankoku8": [], "koujien7": [], "daijisen": [], "jitsuyo": [], "english": []}
    notes = {}
    for nid in col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法* Image:_*'):
        d = dict(zip(fld, col.get_note(nid).fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if not t or "," in t or len(t) < 2: continue
        f, _ = first_jj(t, r)
        key = f if f else ("english" if jmhit(t, r) else None)
        if key in cand and len(cand[key]) < CANDS:
            cand[key].append((t, r)); notes[(t, r)] = d
        if all(len(v) >= CANDS for v in cand.values()): break
    col.close()

    targets = {tr for lst in cand.values() for tr in lst}
    terms = {t for t, r in targets}; readings = {r for t, r in targets if r}
    jj_html = {}
    for f, _ in JJ_CHAIN:
        ents = find_entries(f, terms, readings)
        for (t, r) in targets:
            e = pick(ents, t, r)
            if e: jj_html[(f, t, r)] = render_sc(e[5])
    jm_ents = find_entries("jmdict", terms, readings)
    jm_html = {(t, r): jmdict_glosses(pick_all(jm_ents, t, r)) for (t, r) in targets}

    # per tier, choose the richest-styled example
    chosen = {}
    for f, name in JJ_CHAIN + [("english", "English-only")]:
        lst = cand.get(f, [])
        if not lst: continue
        best = max(lst, key=lambda tr: richness(jj_html.get((f, *tr), "")) if f != "english" else 0)
        chosen[f] = best

    # copy media for the chosen
    msrc = os.path.expanduser("~/.local/share/Anki2/User 1/collection.media")
    os.makedirs("media", exist_ok=True)
    for tr in chosen.values():
        for fldname in ("Image", "Term Audio", "Sentence Audio"):
            for a, b in re.findall(r'src="([^"]+)"|\[sound:([^\]]+)\]', notes[tr].get(fldname, "")):
                nm = a or b
                if nm and os.path.exists(os.path.join(msrc, nm)):
                    shutil.copy(os.path.join(msrc, nm), os.path.join("media", nm))

    # scoped native dict stylesheets (only those that ship one)
    dict_css = ""
    for f, _ in JJ_CHAIN:
        sp = f"{DICTS}/{f}/styles.css"
        if os.path.exists(sp):
            dict_css += "\n" + scope_css(open(sp, encoding="utf-8").read(), f'.aa-jj[data-dict="{f}"]')

    cards = ""
    order = [("sankoku8", "三省堂 primary"), ("koujien7", "広辞苑 fallback"),
             ("jitsuyo", "実用 fallback"), ("daijisen", "大辞泉 fallback"), ("english", "English-only")]
    for key, label in order:
        tr = chosen.get(key)
        if not tr: continue
        t, r = tr; d = notes[tr]
        f, fname = first_jj(t, r)
        jjblock = f'<div class="aa-jj" data-dict="{f}">{jj_html.get((f,t,r),"")}</div>' if f else ""
        je = jm_html.get((t, r), "")
        meaning = jjblock + (f'<div class="aa-je">{je}</div>' if je else "")
        fields = {k: shim_sound(fix_media(v2)) for k, v2 in d.items() if isinstance(v2, str)}
        fields["Meaning"] = meaning
        fields["Expanded Meaning"] = ""; fields["Collapsed Meaning"] = ""
        back = render(kback, fields)
        doc = (f"<!doctype html><html><head><meta charset=utf-8><style>html,body{{margin:0}}body{{padding:10px}}\n"
               f"{kcss}\n{AA_CSS}\n{dict_css}\n</style></head><body class=card>{back}</body></html>")
        cards += (f'<div class="col"><h3>{label} — {html.escape(t)}（{html.escape(r)}）'
                  f'<span class="src">J-J: {fname or "—"}</span></h3>'
                  f'<iframe srcdoc="{html.escape(doc, quote=True)}"></iframe></div>')

    open("mockup.html", "w", encoding="utf-8").write(PAGE.replace("__CARDS__", cards))
    print("wrote mockup.html; chosen:", {k: chosen[k][0] for k in chosen})


# styling the feature owns for the assembled Meaning (native dict CSS layered on top via dict_css)
AA_CSS = """
.aa-jj { text-align: left; font-family: "Hiragino Mincho ProN","Noto Serif CJK JP",serif;
  font-size: 15px; line-height: 1.8; }
.aa-jj ruby rt { font-size: .55em; opacity: .85; font-weight: 400; }
.aa-jj ul, .aa-jj ol { margin: .25em 0; padding-left: 1.3em; }
.aa-jj li { margin: .1em 0; }
.aa-jj a { color: inherit; text-decoration: none; border-bottom: 1px dotted rgba(0,0,0,.18); }
.nightMode .aa-jj a { border-bottom-color: rgba(255,255,255,.25); }
.aa-je { text-align: left; margin-top: 11px; padding-top: 8px; border-top: 1px solid rgba(0,0,0,.12);
  font-size: 13.5px; line-height: 1.6; color: #555; }
.nightMode .aa-je { color:#bbb; border-top-color: rgba(255,255,255,.15); }
"""

PAGE = """<!doctype html><html><head><meta charset=utf-8>
<title>AnkiAlign — enriched card mockup</title>
<style>
 body{font-family:system-ui,sans-serif;margin:0;background:#e9e9ec;color:#222}
 .bar{position:sticky;top:0;background:#1f2430;color:#eee;padding:10px 16px;z-index:5}
 .bar label{margin-left:14px;font-size:13px;cursor:pointer}
 .grid{display:flex;flex-wrap:wrap;gap:14px;padding:16px;align-items:flex-start}
 .col{flex:0 0 370px}
 h3{font-size:13px;margin:0 0 6px;color:#333}.src{color:#888;margin-left:8px;font-weight:400}
 iframe{width:100%;height:660px;border:1px solid #ccc;border-radius:10px;background:#fff}
</style></head><body>
<div class="bar"><b>AnkiAlign · enriched-card mockup</b>
 <label><input type=checkbox id=dark> dark</label>
 <span style="font-size:12px;opacity:.7;margin-left:10px">native dict styles.css scoped in + furigana/list polish</span></div>
<div class="grid">__CARDS__</div>
<script>
const dark=document.getElementById('dark');
dark.onchange=()=>{for(const f of document.querySelectorAll('iframe')){
 const run=()=>{try{f.contentDocument.body.className='card'+(dark.checked?' nightMode night_mode':'');}catch(e){}};f.onload=run;run();}};
</script></body></html>"""

if __name__ == "__main__":
    main()
