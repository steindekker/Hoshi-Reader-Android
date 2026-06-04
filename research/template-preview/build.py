#!/usr/bin/env python3
"""Build a side-by-side card preview (Kinomoto / Lapis / crop-theft) from one real note.

Reads the chosen note fields from /tmp/note_rich.json, the Kinomoto template from
/tmp/kino_*.html + /tmp/kino_css_f3.css, and the fetched Lapis/crop sources in src/.
Emits preview.html. Media is referenced from ./media/ (already populated).
"""
import json, re, html
from pathlib import Path

ROOT = Path(__file__).parent
NOTE = json.load(open("/tmp/note_rich.json"))

# ---------------------------------------------------------------- jrp markup ->
def jrp_to_anki(s: str) -> str:
    """jidoujisho/JRP sentence markup -> Anki furigana notation ( 漢字[かな])."""
    def unit(m):
        inner = m.group(1)
        return re.sub(r";[^;]*$", "", inner)  # drop trailing ;accent
    s = re.sub(r"\{([^{}]*)\}", unit, s)
    s = re.sub(r"\[([^\[\]|=]*)=([^\[\]]*)\]", r"\1", s)      # [surface=base] -> surface
    s = re.sub(r"\[([^\[\]|]+)\|([^\[\]]+)\]", r" \1[\2]", s)  # [漢字|かな] -> ' 漢字[かな]'
    return s.strip()

# ------------------------------------------------------- Anki field "filters" -
RUBY = re.compile(r"([^\s\[\]<>]+)\[([^\]]+)\]")
def f_furigana(s): return RUBY.sub(r"<ruby>\1<rt>\2</rt></ruby>", s).replace("  ", " ").strip()
def f_kanji(s):    return RUBY.sub(r"\1", s).replace(" ", "")
def f_kana(s):     return RUBY.sub(r"\2", s).replace(" ", "")
def f_text(s):     return re.sub(r"<[^>]+>", "", RUBY.sub(r"\1", s))

# ------------------------------------------------- media / sound rewriting -----
SOUND = re.compile(r"\[sound:([^\]]+)\]")
PLAY_SVG = ('<svg viewBox="0 0 24 24" width="1em" height="1em"><circle cx="12" cy="12" r="11" '
            'fill="#3b82f6"/><path d="M9 7v10l8-5z" fill="#fff"/></svg>')
def shim_sound(s):
    return SOUND.sub(lambda m: f'<a class="replay-button" style="cursor:pointer" '
                               f'onclick="new Audio(\'media/{m.group(1)}\').play()">{PLAY_SVG}</a>', s)
def fix_media(s):
    s = re.sub(r'src="(jidoujisho[^"]+)"', r'src="media/\1"', s)
    s = re.sub(r'url\(("?)_', r'url(\1media/_', s)
    return s

# ----------------------------------------------------------- mustache (fixed) -
def render(tmpl, fields):
    """Resolve {{#F}}{{/F}}, {{^F}}{{/F}}, {{filter:F}}, {{F}} for a single note."""
    def truthy(name): return bool(str(fields.get(name, "")).strip())
    # sections (inner-most first, iterate to fixpoint)
    sec = re.compile(r"\{\{([#^])([^}]+)\}\}(.*?)\{\{/\2\}\}", re.S)
    prev = None
    while prev != tmpl:
        prev = tmpl
        def s(m):
            kind, name, body = m.group(1), m.group(2).strip(), m.group(3)
            show = truthy(name) if kind == "#" else not truthy(name)
            return body if show else ""
        tmpl = sec.sub(s, tmpl)
    # filtered fields
    def fil(m):
        flt, name = m.group(1), m.group(2).strip()
        raw = str(fields.get(name, ""))
        return {"furigana": f_furigana, "kanji": f_kanji, "kana": f_kana,
                "text": f_text}.get(flt, lambda x: x)(raw)
    tmpl = re.sub(r"\{\{(furigana|kanji|kana|text):([^}]+)\}\}", fil, tmpl)
    # plain fields
    tmpl = re.sub(r"\{\{([^}#^/]+)\}\}", lambda m: str(fields.get(m.group(1).strip(), "")), tmpl)
    return tmpl

# ============================================================ field values =====
term      = NOTE["Term"]
term_furi = NOTE["Furigana"].strip()          # ' 異変[いへん]'
reading   = NOTE["Reading"]
sent_anki = jrp_to_anki(NOTE["Sentence"])      # ' 異変[いへん]に 気付[きづ]いた...'
sent_plain= f_kanji(sent_anki)
sent_furi_html = f_furigana(sent_anki)
# bold the cloze target inside the furigana sentence (for Lapis pitch coloring)
tgt = NOTE["Cloze Inside"].strip()
if tgt:
    tr = f_furigana(jrp_to_anki("{[%s|%s];0}" % (term, reading))) if False else None
    sent_furi_bold = re.sub(re.escape(f_furigana(term_furi)), lambda m: f"<b>{m.group(0)}</b>",
                            sent_furi_html, count=1)
    if sent_furi_bold == sent_furi_html:  # fallback: bold plain term
        sent_furi_bold = sent_furi_html.replace(term, f"<b>{term}</b>", 1)
else:
    sent_furi_bold = sent_furi_html
meaning   = NOTE["Meaning"]
pitch_svg = NOTE["Pitch Accent"]
image     = NOTE["Image"]
term_audio= NOTE["Term Audio"]
freq      = NOTE["Frequency"]

# ============================================================ KINOMOTO =========
kino_css   = fix_media(Path("/tmp/kino_css_f3.css").read_text())
kino_front = Path("/tmp/kino_FRONT.html").read_text()
kino_back  = Path("/tmp/kino_BACK.html").read_text()
kino_fields = {k: shim_sound(fix_media(v)) for k, v in NOTE.items()}
kino_front_h = render(kino_front, kino_fields)
kino_back_h  = render(kino_back,  kino_fields)

# ============================================================ CUSTOM ===========
# base = Kinomoto + JRP (same HTML). fonts = Lapis stacks. sizes = Kinomoto (untouched).
LAPIS_SERIF = ('"Hiragino Mincho ProN", "Noto Serif CJK JP", "Noto Serif JP", '
               '"Yu Mincho", HanaMinA, HanaMinB, serif')
LAPIS_SANS  = ('"Inter", "SF Pro Display", "Liberation Sans", "Segoe UI", '
               '"Hiragino Kaku Gothic ProN", "Noto Sans CJK JP", "Noto Sans JP", '
               '"Meiryo", HanaMinA, HanaMinB, sans-serif')
custom_css = kino_css + f"""
/* ---- AnkiAlign custom ------------------------------------------------
   Lapis serif everywhere + Lapis "feel" (bg / text / elevated card),
   toned down: softer cream, muted accent, lighter shadows.
   Kinomoto font-sizes kept untouched. -------------------------------- */
.card {{ font-family: {LAPIS_SERIF}; font-weight: 500; }}

.card:not(.nightMode) {{
  --bg: #fbf8f1;            /* Lapis cream #fffaf0, softened */
  --fg: #383838;
  --panel: rgba(0,0,0,.035);     /* Lapis --bg-elevated, lighter */
  --panel-border: rgba(0,0,0,.10);
  --accent: #5566b5;             /* Lapis bold #4660f1, muted */
  --shadow: 0 1px 3px rgba(0,0,0,.12);
}}
.card.nightMode {{
  --bg: #272727;            /* Lapis dark #2c2c2c, slightly deeper */
  --fg: #e7e4dd;
  --panel: rgba(255,255,255,.05);
  --panel-border: rgba(255,255,255,.13);
  --accent: #d7dc8a;             /* Lapis dark bold #fffd9e, muted */
  --shadow: none;
}}

.card:not(.nightMode) {{ background-color: var(--bg); }}
.card.nightMode      {{ background-color: var(--bg); }}
.card                {{ color: var(--fg); }}

/* definition becomes the Lapis-style elevated panel (left accent border) */
.meaning, .nightMode .meaning {{
  background: var(--panel);
  border-left: 3px solid var(--panel-border);
  border-radius: 8px;
  box-shadow: none;
}}

.image img {{ box-shadow: var(--shadow); }}
.cloze     {{ color: var(--accent); }}
/* drop Kinomoto's white emboss text-shadows (built for the old grey bg) */
.details-summary, .furigana {{ text-shadow: none; }}

/* hide inline sentence furigana — the JRP unit pop-up already shows reading+pitch
   on hover/tap. pitch coloring + indicator bars stay; term reading at top stays. */
#sentence rt {{ display: none; }}
"""

# ============================================================ LAPIS ============
lapis_css  = Path(ROOT/"src/lapis/styling.css").read_text()
lapis_front= Path(ROOT/"src/lapis/front.html").read_text()
lapis_back = Path(ROOT/"src/lapis/back.html").read_text()
lapis_fields = {
    "Expression": term, "ExpressionFurigana": term_furi, "ExpressionReading": reading,
    "Sentence": sent_plain, "SentenceFurigana": sent_furi_bold,
    "PitchPosition": pitch_svg, "PitchCategories": "",
    "Frequency": freq, "FreqSort": freq,
    "Glossary": meaning, "MainDefinition": "",
    "Picture": fix_media(image), "DefinitionPicture": "",
    "ExpressionAudio": shim_sound(fix_media(term_audio)), "SentenceAudio": "",
    "Tags": "", "Hint": "", "MiscInfo": "", "SelectionText": "",
    # card-type flags (vocab card => all empty)
    "IsSentenceCard": "", "IsWordAndSentenceCard": "", "IsClickCard": "", "IsAudioCard": "",
}
lapis_front_h = render(lapis_front, lapis_fields)
lapis_back_h  = render(lapis_back,  lapis_fields)

# ============================================================ CROP-THEFT =======
crop_css   = Path(ROOT/"src/crop/styling.css").read_text()
crop_front = Path(ROOT/"src/crop/front_template.html").read_text()
crop_back  = Path(ROOT/"src/crop/back_template.html").read_text()
crop_fields = {
    "Word": term, "Reading": reading, "PitchPattern": pitch_svg,
    "Example Sentence": sent_anki, "Example Target": "",
    "Notes": "", "Audio": shim_sound(fix_media(term_audio)), "Definition": meaning,
    "FrontSide": render(crop_front, {"Word": term}),
}
crop_front_h = render(crop_front, crop_fields)
crop_back_h  = render(crop_back,  crop_fields)

# ============================================================ assemble docs ====
def doc(css, body):
    """Standalone card doc: <html><body class=card> ... matches Anki's wrapper."""
    return f"""<!doctype html><html><head><meta charset=utf-8>
<style>html,body{{margin:0}} body{{padding:10px}}
{css}
</style></head><body class="card">{body}</body></html>"""

docs = {
    "kino_front":   doc(kino_css,   kino_front_h),
    "kino_back":    doc(kino_css,   kino_back_h),
    "custom_front": doc(custom_css, kino_front_h),
    "custom_back":  doc(custom_css, kino_back_h),
    "lapis_front":  doc(lapis_css,  lapis_front_h),
    "lapis_back":   doc(lapis_css,  lapis_back_h),
    "crop_front":   doc(crop_css,   crop_front_h),
    "crop_back":    doc(crop_css,   crop_back_h),
}

def esc(s): return html.escape(s, quote=True)

cols = [("jidoujisho Kinomoto — current", "kino"),
        ("Custom — Lapis fonts + Kinomoto sizes", "custom")]

frames = ""
for title, key in cols:
    frames += f"""<div class="col">
  <h2>{title}</h2>
  <div class="side"><span class="lbl">FRONT</span>
    <iframe srcdoc="{esc(docs[key+'_front'])}"></iframe></div>
  <div class="side"><span class="lbl">BACK</span>
    <iframe srcdoc="{esc(docs[key+'_back'])}"></iframe></div>
</div>"""

PREVIEW = """<!doctype html><html><head><meta charset=utf-8>
<title>AnkiAlign template comparison</title>
<style>
  body{font-family:system-ui,sans-serif;margin:0;background:#e9e9ec;color:#222}
  .bar{position:sticky;top:0;z-index:10;background:#1f2430;color:#eee;padding:10px 16px;
       display:flex;gap:18px;align-items:center;flex-wrap:wrap}
  .bar b{font-size:15px} .bar label{font-size:13px;cursor:pointer}
  .note{font-size:12px;opacity:.7}
  .grid{display:flex;gap:14px;padding:16px;align-items:flex-start;overflow-x:auto}
  .col{flex:1;min-width:var(--cw,360px);max-width:var(--cw,360px)}
  h2{font-size:13px;text-align:center;margin:0 0 8px;color:#333}
  .side{margin-bottom:14px}
  .lbl{font-size:10px;letter-spacing:.1em;color:#888;display:block;margin:0 0 3px 2px}
  iframe{width:100%;border:1px solid #ccc;border-radius:8px;background:#fff;height:520px}
</style></head><body>
<div class="bar">
  <b>AnkiAlign · template comparison</b>
  <span class="note">note: 異変 (real card, deck Default)</span>
  <label><input type="checkbox" id="dark"> dark mode</label>
  <label><input type="checkbox" id="mobile"> mobile width</label>
  <span class="note" id="hint">desktop width — toggle to see AnkiDroid layout</span>
</div>
<div class="grid">__FRAMES__</div>
<script>
const dark=document.getElementById('dark'), mob=document.getElementById('mobile');
const grid=document.querySelector('.grid');
function apply(){
  const isM=mob.checked, isD=dark.checked;
  document.documentElement.style.setProperty('--cw', isM?'390px':'520px');
  for(const f of document.querySelectorAll('iframe')){
    const run=()=>{ try{
      const d=f.contentDocument; if(!d) return;
      d.documentElement.className = isM?'mobile':'';
      d.body.className = 'card'+(isD?' nightMode night_mode night-mode':'');
      // auto-size
      setTimeout(()=>{ try{f.style.height=Math.max(d.body.scrollHeight+24,200)+'px';}catch(e){} }, 60);
    }catch(e){} };
    if(f.contentDocument && f.contentDocument.readyState==='complete') run();
    f.onload=run; run();
  }
  document.getElementById('hint').textContent =
    (isM?'mobile width (~AnkiDroid)':'desktop width')+(isD?' · dark':' · light');
}
dark.onchange=apply; mob.onchange=apply;
window.addEventListener('load', ()=>setTimeout(apply,120));
</script></body></html>"""

out = PREVIEW.replace("__FRAMES__", frames)
(ROOT/"preview.html").write_text(out)
print("wrote", ROOT/"preview.html", f"({len(out)//1024} KB)")
print("sentence (plain):", sent_plain)
print("sentence (furi notation):", sent_anki)
