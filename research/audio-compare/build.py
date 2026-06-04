#!/usr/bin/env python3
"""Build a static A/B/C/D listening site to compare the four local-audio-yomichan
sources (nhk16 / shinmeikai8 / jpod / forvo) on a fixed set of 20 words.

Reads the add-on's on-disk indexes directly (entries.db is built lazily by Anki
and may be empty), copies the matched .opus files into site/media/<source>/, and
emits site/index.html. Pure stdlib.

Run:  python3 build.py  then  python3 -m http.server -d site 8137
"""
import json
import shutil
from html import escape
from pathlib import Path

UF = Path.home() / ".local/share/Anki2/addons21/1045800357/user_files"
OUT = Path(__file__).parent / "site"
MEDIA = OUT / "media"

# (term, reading, gloss, standard-accent downstep number for reference)
# First 10 are classic minimal pairs where ACCENT is the only audible difference —
# the sharpest test of a source's pitch-accent accuracy.
WORDS = [
    ("箸", "はし", "chopsticks", 1),
    ("橋", "はし", "bridge", 2),
    ("端", "はし", "edge", 0),
    ("雨", "あめ", "rain", 1),
    ("飴", "あめ", "candy", 0),
    ("花", "はな", "flower", 2),
    ("鼻", "はな", "nose", 0),
    ("神", "かみ", "god", 1),
    ("髪", "かみ", "hair", 2),
    ("紙", "かみ", "paper", 2),
    ("日本", "にほん", "Japan", 2),
    ("先生", "せんせい", "teacher", 3),
    ("学校", "がっこう", "school", 0),
    ("言葉", "ことば", "word", 3),
    ("面白い", "おもしろい", "interesting", 4),
    ("食べる", "たべる", "to eat", 2),
    ("時間", "じかん", "time", 0),
    ("友達", "ともだち", "friend", 0),
    ("電話", "でんわ", "telephone", 0),
    ("飛行機", "ひこうき", "airplane", 2),
]

SOURCES = ["nhk16", "shinmeikai8", "jpod", "forvo"]


def load_yomichan_index(name):
    """jpod / shinmeikai8: {headwords:{term:[file]}, files:{file:{kana_reading,...}}}"""
    idx = json.loads((UF / f"{name}_files/index.json").read_text(encoding="utf-8", errors="ignore"))
    return idx.get("headwords", {}), idx.get("files", {})


def load_nhk16():
    """Map (term -> [(soundFile, reading, accent_str)]) from entries.json."""
    raw = (UF / "nhk16_files/entries.json").read_text(encoding="utf-8", errors="ignore")
    entries = json.loads(raw)
    out = {}
    for e in entries:
        reading = e.get("kana", "")
        terms = list(e.get("kanji", [])) or [reading]
        for acc in e.get("accents", []):
            sf = acc.get("soundFile")
            if not sf:
                continue
            segs = acc.get("accent", [])
            accent_str = "·".join(str(s.get("pitchAccent", "?")) for s in segs)
            for t in terms + [reading]:
                out.setdefault(t, []).append((sf, reading, accent_str))
    return out


def find_jpod_like(name, headwords, files, term, reading):
    hits = []
    for f in headwords.get(term, []):
        meta = files.get(f, {})
        r = meta.get("kana_reading", "")
        if r and reading and r != reading:
            label = f"reading={r} ≠ {reading}  pitch={meta.get('pitch_number','?')}"
            match = False
        else:
            label = f"pitch={meta.get('pitch_number','?')}" + (
                f"  {meta.get('pitch_pattern')}" if meta.get('pitch_pattern') else "")
            match = True
        hits.append((UF / f"{name}_files/media/{f}", label, match))
    # prefer reading-matched, but keep mismatches visible (labelled)
    hits.sort(key=lambda h: not h[2])
    return hits


def find_forvo(term, reading):
    hits = []
    for key in (term, reading):
        for p in UF.glob(f"forvo_files/*/{key}.opus"):
            hits.append((p, f"speaker: {p.parent.name}" + ("" if key == term else f"  (kana: {key})"), True))
    return hits


def find_nhk16(nhk, term, reading):
    hits = []
    seen = set()
    for sf, r, accent_str in nhk.get(term, []):
        if reading and r and r != reading:
            continue
        if sf in seen:
            continue
        seen.add(sf)
        hits.append((UF / f"nhk16_files/audio/{sf}", f"accent=[{accent_str}]  reading={r}", True))
    return hits


def copy_to_site(src_path, source, term):
    if not src_path.is_file():
        return None
    dest_dir = MEDIA / source
    dest_dir.mkdir(parents=True, exist_ok=True)
    safe = f"{term}_{src_path.stem}{src_path.suffix}"
    dest = dest_dir / safe
    shutil.copy2(src_path, dest)
    return f"media/{source}/{safe}"


def cell_html(hits, source, term):
    if not hits:
        return '<span class="miss">—</span>'
    out = []
    for path, label, match in hits:
        rel = copy_to_site(path, source, term)
        if not rel:
            continue
        cls = "" if match else ' class="mismatch"'
        out.append(
            f'<div class="clip{("" if match else " warn")}">'
            f'<audio controls preload="none" src="{escape(rel)}"></audio>'
            f'<div class="lbl"{cls}>{escape(label)}</div></div>'
        )
    return "\n".join(out) or '<span class="miss">—</span>'


def build():
    if MEDIA.exists():
        shutil.rmtree(MEDIA)
    jp_hw, jp_f = load_yomichan_index("jpod")
    sh_hw, sh_f = load_yomichan_index("shinmeikai8")
    nhk = load_nhk16()

    rows = []
    coverage = {s: 0 for s in SOURCES}
    for term, reading, gloss, acc in WORDS:
        cells = {
            "nhk16": find_nhk16(nhk, term, reading),
            "shinmeikai8": find_jpod_like("shinmeikai8", sh_hw, sh_f, term, reading),
            "jpod": find_jpod_like("jpod", jp_hw, jp_f, term, reading),
            "forvo": find_forvo(term, reading),
        }
        for s in SOURCES:
            if any(h[2] for h in cells[s]):
                coverage[s] += 1
        tds = "".join(f"<td>{cell_html(cells[s], s, term)}</td>" for s in SOURCES)
        rows.append(
            f'<tr><th class="word"><ruby>{escape(term)}<rt>{escape(reading)}</rt></ruby>'
            f'<div class="gloss">{escape(gloss)}</div>'
            f'<div class="acc">std accent [{acc}]</div></th>{tds}</tr>'
        )

    cov = "  ·  ".join(f"{s}: {coverage[s]}/{len(WORDS)}" for s in SOURCES)
    head = "".join(f"<th>{s}</th>" for s in SOURCES)
    html = TEMPLATE.format(rows="\n".join(rows), head=head, coverage=escape(cov))
    OUT.mkdir(exist_ok=True)
    (OUT / "index.html").write_text(html, encoding="utf-8")
    print("coverage (reading-matched):", cov)
    print(f"wrote {OUT/'index.html'}")


TEMPLATE = """<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Word-audio source comparison</title>
<style>
  body{{font-family:system-ui,sans-serif;margin:0;padding:1.5rem;background:#111;color:#eee}}
  h1{{font-size:1.25rem;margin:0 0 .25rem}}
  .note{{color:#9aa;font-size:.85rem;margin:0 0 1rem;line-height:1.5}}
  .cov{{color:#7fd1b9;font-size:.8rem;margin-bottom:1rem}}
  table{{border-collapse:collapse;width:100%}}
  th,td{{border:1px solid #333;padding:.5rem;vertical-align:top;text-align:left}}
  thead th{{position:sticky;top:0;background:#1d1d1d;text-transform:uppercase;font-size:.75rem;letter-spacing:.05em}}
  th.word{{width:9rem;background:#181818}}
  th.word ruby{{font-size:1.6rem}} rt{{font-size:.7rem;color:#9aa}}
  .gloss{{color:#9aa;font-size:.8rem;margin-top:.25rem}}
  .acc{{color:#d8a657;font-size:.75rem;margin-top:.15rem;font-variant-numeric:tabular-nums}}
  .clip{{margin-bottom:.6rem}}
  .clip.warn{{opacity:.85}}
  audio{{width:200px;height:34px}}
  .lbl{{font-size:.7rem;color:#9aa;margin-top:.15rem}}
  .lbl.mismatch{{color:#e06c75}}
  .miss{{color:#555}}
  tr:nth-child(even) td{{background:#161616}}
</style></head>
<body>
<h1>Word-audio source comparison · 20 words</h1>
<p class="note">First 10 rows are minimal pairs (はし・あめ・はな・かみ) where <b>pitch accent is the only audible difference</b> — the sharpest test of accuracy. "std accent [n]" is the reference downstep position (0 = heiban). Compare each clip against it. Red labels = the source's stored reading didn't match the card reading (possible wrong word).</p>
<p class="cov">reading-matched coverage — {coverage}</p>
<table><thead><tr><th class="word">word</th>{head}</tr></thead>
<tbody>
{rows}
</tbody></table>
</body></html>
"""

if __name__ == "__main__":
    build()
