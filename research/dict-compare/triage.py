#!/usr/bin/env python3
"""Build a manual triage page for the 195 cards with no J-J definition.

Each card shows its word / reading / source / category and its real current
Meaning, with decision buttons (keep / drop / fix-term / multiword) pre-set to a
suggested action. Export writes decisions.json (nid -> decision) which apply.py
then enacts (backup + tag keeps / delete drops).

Run: uv run --with anki python triage.py
Then open http://localhost:8732/triage.html
"""
import json, glob, re, os, html
from pathlib import Path
from anki.collection import Collection

ROOT = Path(__file__).parent
DICTS = ROOT / "dicts"
COL_SRC = os.path.expanduser("~/.local/share/Anki2/User 1/collection.anki2")
COL_COPY = "/tmp/dc_triage.anki2"


def load_index(folder):
    ex, rd = set(), set()
    for tb in glob.glob(str(DICTS / folder / "term_bank_*.json")):
        for e in json.load(open(tb, encoding="utf-8")):
            ex.add(e[0])
            if e[1]:
                rd.add(e[1])
    return ex, rd


def clean_term(t):
    t = re.sub(r"<[^>]+>", "", t)
    t = re.sub(r"\[([^\]|]+)\|[^\]]+\]", r"\1", t)
    t = re.sub(r"[（(][ぁ-んァ-ヶー]+[）)]\s*$", "", t)
    return t.strip()


def classify_origin(m):
    if not m.strip():
        return "empty"
    if re.search(r"\[[^\]]*;[^\]]*\]", m) and "★" in m:
        return "KanjiStudy"
    if re.search(r"\[[^\]]*;[^\]]*\]", m):
        return "KanjiStudy?"
    if "data-sc" in m or m.count("【") >= 2:
        return "Kinomoto/yomitan"
    if "【" in m and "<" not in m:
        return "Yuuna"
    if "<" in m:
        return "MyNote/HTML"
    return "plain"


def main():
    import shutil
    idx = {f: load_index(f) for f in ("sankoku8", "koujien7", "jitsuyo", "jmdict")}
    JJ_ex = idx["sankoku8"][0] | idx["koujien7"][0] | idx["jitsuyo"][0]
    JJ_rd = idx["sankoku8"][1] | idx["koujien7"][1] | idx["jitsuyo"][1]
    JM_ex, JM_rd = idx["jmdict"]
    ALL_ex, ALL_rd = JJ_ex | JM_ex, JJ_rd | JM_rd

    def jj_hit(t, r):
        return t in JJ_ex or (r and (r in JJ_ex or r in JJ_rd)) or t in JJ_rd

    def any_hit(t, r):
        return t in ALL_ex or (r and (r in ALL_ex or r in ALL_rd)) or t in ALL_rd

    shutil.copy(COL_SRC, COL_COPY)
    col = Collection(COL_COPY)
    m = col.models.by_name("jidoujisho Kinomoto")
    fld = [f["name"] for f in m["flds"]]
    nids = col.find_notes('note:"jidoujisho Kinomoto" -deck:*Core2.3k* -deck:*文法*')
    rows = []
    for nid in nids:
        note = col.get_note(nid)
        d = dict(zip(fld, note.fields))
        t, r = d.get("Term", "").strip(), d.get("Reading", "").strip()
        if not t or jj_hit(t, r):
            continue
        tt = clean_term(t)
        parts = [p.strip() for p in re.split(r"[,、，]", tt) if p.strip()]
        if len(parts) > 1:
            cat, sug = "comma-multi", "multiword"
        elif tt != t and (any_hit(tt, r) or jj_hit(tt, r)):
            cat, sug = "term-markup", "fix"
        elif any_hit(t, r):
            cat, sug = "jmdict-only", "keep"
        else:
            cat, sug = "no-dict", "drop"
        rows.append({
            "nid": nid, "t": t, "r": r, "clean": tt,
            "deck": col.decks.name(note.cards()[0].did).split("::")[0],
            "origin": classify_origin(d.get("Meaning", "")),
            "cat": cat, "sug": sug,
            "meaning": d.get("Meaning", ""),
        })
    col.close()

    # attach duplicate info (from dupes.py) and let full-dups default to drop
    dupes = {}
    dp = ROOT / "dupes.json"
    if dp.exists():
        dupes = json.loads(dp.read_text(encoding="utf-8"))
    FULL_DUP = {"dup", "multi-all-dup"}
    for r in rows:
        info = dupes.get(str(r["nid"]))
        r["dup"] = info
        if info:
            kind = info["kind"]
            r["dupkind"] = kind
            covered = [f'{p}✓{",".join(sorted({x["deck"].split("::")[0] for x in dl}))}'
                       for p, dl in info["parts"].items() if dl]
            r["duptxt"] = " · ".join(covered) if covered else ""
            if kind in FULL_DUP:
                r["sug"] = "drop"
        else:
            r["dupkind"] = "unique"
            r["duptxt"] = ""

    CATS = ["comma-multi", "jmdict-only", "term-markup", "no-dict"]
    rows.sort(key=lambda x: (CATS.index(x["cat"]), x["t"]))

    def sanitize(mh):
        mh = re.sub(r"(?is)<(script|style)[^>]*>.*?</\1>", "", mh)
        mh = re.sub(r'src="([^"]+)"', r'src="media/\1"', mh)
        return mh

    cards = ""
    for r in rows:
        actions = ["keep", "fix", "multiword", "drop"]
        radios = ""
        for a in actions:
            chk = " checked" if a == r["sug"] else ""
            radios += (f'<label class="opt {a}"><input type="radio" name="d{r["nid"]}" '
                       f'value="{a}"{chk}>{a}</label>')
        cleaned = f' → <code>{html.escape(r["clean"])}</code>' if r["clean"] != r["t"] else ""
        dupbadge = ""
        hasdup = "0"
        if r["dupkind"] in ("dup", "multi-all-dup"):
            dupbadge = f'<span class="dup full">⚑ duplicate — {html.escape(r["duptxt"])}</span>'
            hasdup = "1"
        elif r["dupkind"] == "multi-some-dup":
            dupbadge = f'<span class="dup part">½ partial dup — {html.escape(r["duptxt"])}</span>'
            hasdup = "1"
        cards += f'''<div class="card" data-nid="{r['nid']}" data-cat="{r['cat']}" data-dup="{hasdup}" data-term="{html.escape(r['t'],quote=True)}">
  <div class="hd">
    <span class="term">{html.escape(r['t'])}{cleaned}</span>
    <span class="read">{html.escape(r['r'])}</span>
    {dupbadge}
    <span class="meta">{r['cat']} · {r['origin']} · {html.escape(r['deck'])}</span>
  </div>
  <div class="mean">{sanitize(r['meaning'])}</div>
  <div class="dec">{radios}</div>
</div>'''

    counts = {c: sum(1 for r in rows if r["cat"] == c) for c in CATS}
    chips = " ".join(f'<button class="chip" data-f="{c}">{c} <b>{counts[c]}</b></button>' for c in CATS)
    ndup = sum(1 for r in rows if r["dupkind"] in ("dup", "multi-all-dup", "multi-some-dup"))
    chips += f' <button class="chip dupchip" data-dup="1">has-duplicate <b>{ndup}</b></button>'

    page = PAGE.replace("__CARDS__", cards).replace("__CHIPS__", chips).replace("__N__", str(len(rows)))
    (ROOT / "triage.html").write_text(page, encoding="utf-8")
    print(f"wrote triage.html — {len(rows)} cards", counts)


PAGE = r"""<!doctype html><html lang=ja><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>AnkiAlign · triage 195</title>
<style>
  body{font-family:system-ui,sans-serif;margin:0;background:#ececed;color:#1d1d1f}
  .bar{position:sticky;top:0;z-index:10;background:#1f2430;color:#eee;padding:10px 16px;
       display:flex;gap:10px;align-items:center;flex-wrap:wrap}
  .bar b{color:#ffd479}
  .chip{background:#333a49;color:#ddd;border:1px solid #4a5365;border-radius:14px;
        padding:4px 10px;font-size:12px;cursor:pointer}
  .chip.active{background:#5566b5;color:#fff}
  .chip b{margin-left:4px}
  .spacer{flex:1}
  .exp{background:#3fae6b;color:#fff;border:0;border-radius:6px;padding:7px 14px;font-size:13px;cursor:pointer}
  .prog{font-size:12px;opacity:.85}
  .wrap{max-width:920px;margin:0 auto;padding:14px}
  .card{background:#fff;border:1px solid #dadade;border-radius:10px;margin:0 0 12px;padding:12px 14px}
  .card.drop{opacity:.5}
  .hd{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:8px}
  .term{font-size:20px;font-weight:600}
  .term code{font-size:15px;color:#2a7}
  .read{color:#666;font-size:14px}
  .dup{font-size:11px;font-weight:600;padding:2px 8px;border-radius:10px}
  .dup.full{background:#fde0e0;color:#b13434;border:1px solid #f1b8b8}
  .dup.part{background:#fdf0d8;color:#9a6a18;border:1px solid #ecd3a0}
  .meta{margin-left:auto;font-size:11px;color:#999;letter-spacing:.02em}
  .mean{font-size:13px;line-height:1.55;max-height:150px;overflow:auto;
        background:#fafafa;border:1px solid #eee;border-radius:6px;padding:8px 10px;
        color:#333;word-break:break-word}
  .mean img{max-height:90px}
  .dec{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
  .opt{font-size:12px;padding:4px 10px;border-radius:6px;border:1px solid #ccc;cursor:pointer;user-select:none}
  .opt input{margin-right:5px}
  .opt.keep{border-color:#3fae6b} .opt.fix{border-color:#3b82f6}
  .opt.multiword{border-color:#c08a2e} .opt.drop{border-color:#d05656}
  .opt:has(input:checked).keep{background:#e6f6ec}
  .opt:has(input:checked).fix{background:#e7f0ff}
  .opt:has(input:checked).multiword{background:#fbf0dc}
  .opt:has(input:checked).drop{background:#fde6e6}
</style></head><body>
<div class="bar">
  <b>triage __N__</b> &nbsp; legend:
  <span class="prog">keep=enrich/leave · fix=clean Term then enrich · multiword=tag&amp;skip · drop=delete</span>
  __CHIPS__
  <span class="spacer"></span>
  <span class="prog" id="prog"></span>
  <button class="exp" id="export">⬇ Export decisions</button>
</div>
<div class="wrap">__CARDS__</div>
<script>
const chips=[...document.querySelectorAll('.chip')];
chips.forEach(c=>c.onclick=()=>{
  c.classList.toggle('active');
  const onCat=chips.filter(x=>x.classList.contains('active')&&x.dataset.f).map(x=>x.dataset.f);
  const dupOn=document.querySelector('.dupchip')?.classList.contains('active');
  for(const card of document.querySelectorAll('.card')){
    const okCat=!onCat.length||onCat.includes(card.dataset.cat);
    const okDup=!dupOn||card.dataset.dup==='1';
    card.style.display=(okCat&&okDup)?'':'none';
  }
});
function refresh(){
  for(const card of document.querySelectorAll('.card')){
    const v=card.querySelector('input:checked')?.value;
    card.classList.toggle('drop', v==='drop');
  }
  const tot=document.querySelectorAll('.card').length;
  const tally={};
  for(const i of document.querySelectorAll('input:checked')) tally[i.value]=(tally[i.value]||0)+1;
  document.getElementById('prog').textContent =
    Object.entries(tally).map(([k,n])=>`${k}:${n}`).join('  ') + `  (of ${tot})`;
}
document.addEventListener('change',e=>{if(e.target.type==='radio')refresh();});
document.getElementById('export').onclick=()=>{
  const out=[];
  for(const card of document.querySelectorAll('.card')){
    const v=card.querySelector('input:checked')?.value;
    out.push({nid:Number(card.dataset.nid),term:card.dataset.term,decision:v});
  }
  const blob=new Blob([JSON.stringify(out,null,1)],{type:'application/json'});
  const a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download='decisions.json'; a.click();
};
refresh();
</script></body></html>"""

if __name__ == "__main__":
    main()
