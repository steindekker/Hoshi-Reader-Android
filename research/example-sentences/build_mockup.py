#!/usr/bin/env python3
"""Build a standalone interactive mockup (mockup.html) from data.json.

data.json holds 10 real Kinomoto sample words, each enriched with example
sentences from FIVE sources:
  - Jitendex        (J->E, Tatoeba examples, with furigana)   [owned]
  - NEW斎藤和英      (J->E, curated, huge volume)               [owned]
  - 研究社 新英和    (E->J, reverse-indexed -> JP collocations) [owned]
  - Massif          (massif.la, web-novel corpus, scraped)
  - Immersion Kit   (apiv2, anime; audio/img placeholder)
The mockup embeds the data and lets you toggle the design decisions live.
Run: python build_mockup.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).parent
data = json.load(open(ROOT / "data.json", encoding="utf-8"))

HTML = r"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Example-sentences mockup — anki-align</title>
<style>
:root{
  --bg:#fbf8f1; --fg:#383838; --panel:rgba(0,0,0,.035); --panel-border:rgba(0,0,0,.10);
  --accent:#5566b5; --shadow:0 1px 3px rgba(0,0,0,.12); --muted:#8a8577;
  --jitendex:#2f6f9f; --saito:#3f7d54; --kenkyusha:#7d5bbf; --massif:#9a5b2e; --ik:#b5556b;
}
html.dark{
  --bg:#272727; --fg:#e7e4dd; --panel:rgba(255,255,255,.05); --panel-border:rgba(255,255,255,.13);
  --accent:#d7dc8a; --shadow:none; --muted:#9a958a;
  --jitendex:#7fb6dd; --saito:#86c79a; --kenkyusha:#b69aee; --massif:#d6a06a; --ik:#e6909f;
}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
  font-family:"Hiragino Kaku Gothic ProN","Yu Gothic Medium","Noto Sans CJK JP","Segoe UI",sans-serif;
  line-height:1.7;padding-bottom:4rem}
.bar{position:sticky;top:0;z-index:10;background:var(--bg);border-bottom:1px solid var(--panel-border);
  padding:.6rem 1rem;display:flex;flex-wrap:wrap;gap:.4rem 1rem;align-items:center;
  box-shadow:0 2px 6px rgba(0,0,0,.06);font-size:.8rem}
.bar b{font-size:.72rem;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
.bar label{display:inline-flex;align-items:center;gap:.3rem;cursor:pointer;user-select:none}
.bar .grp{display:inline-flex;gap:.6rem;align-items:center;padding:.15rem .55rem;
  border:1px solid var(--panel-border);border-radius:7px}
.wrap{max-width:880px;margin:1.4rem auto;padding:0 1rem;display:flex;flex-direction:column;gap:1.5rem}
.card{background:var(--panel);border:1px solid var(--panel-border);border-radius:12px;
  padding:1rem 1.15rem;box-shadow:var(--shadow)}
.head{display:flex;align-items:baseline;gap:.7rem;flex-wrap:wrap}
.term{font-size:1.9rem;font-weight:700}
.read{color:var(--muted);font-size:1rem}
.freq{margin-left:auto;font-size:.72rem;color:var(--muted);border:1px solid var(--panel-border);
  border-radius:20px;padding:.1rem .6rem}
.meaning{font-size:.9rem;opacity:.85;margin:.35rem 0 .2rem}
.deck-sentence{font-size:1.05rem;margin:.5rem 0 .2rem;padding:.5rem .7rem;border-left:3px solid var(--panel-border);
  background:rgba(127,127,127,.05);border-radius:0 6px 6px 0}
details.ex{margin-top:.8rem;border-top:1px dashed var(--panel-border);padding-top:.5rem}
details.ex>summary{cursor:pointer;font-size:.86rem;color:var(--accent);font-weight:600;list-style:none}
details.ex>summary::-webkit-details-marker{display:none}
details.ex>summary::before{content:"▸ ";font-size:.8em}
details.ex[open]>summary::before{content:"▾ "}
.src-block{margin:.7rem 0 .2rem}
.src-h{font-size:.73rem;font-weight:700;letter-spacing:.03em;text-transform:uppercase;
  display:flex;align-items:center;gap:.4rem;margin-bottom:.3rem}
.src-h .dot{width:.6rem;height:.6rem;border-radius:50%}
.src-jitendex .dot{background:var(--jitendex)} .src-jitendex .src-h{color:var(--jitendex)}
.src-saito .dot{background:var(--saito)} .src-saito .src-h{color:var(--saito)}
.src-kenkyusha .dot{background:var(--kenkyusha)} .src-kenkyusha .src-h{color:var(--kenkyusha)}
.src-massif .dot{background:var(--massif)} .src-massif .src-h{color:var(--massif)}
.src-ik .dot{background:var(--ik)} .src-ik .src-h{color:var(--ik)}
ul.exlist{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:.45rem}
.cols ul.exlist{flex-direction:row;flex-wrap:wrap}
.cols .ex-item{flex:1 1 calc(50% - .5rem)}
.ex-item{font-size:1.05rem}
.ex-item mark{background:transparent;color:var(--accent);font-weight:700;
  border-bottom:2px solid var(--accent);padding-bottom:1px}
.ex-item .en{display:block;font-size:.82rem;color:var(--muted);margin-top:.05rem}
.ex-item .meta{display:block;font-size:.72rem;color:var(--muted);margin-top:.05rem}
.ex-item .meta a{color:var(--muted)}
rt{font-size:.6em;color:var(--muted)}
.nofuri rt{display:none}
.ik-media{display:inline-flex;align-items:center;gap:.35rem;font-size:.7rem;color:var(--muted);
  border:1px dashed var(--panel-border);border-radius:5px;padding:.05rem .4rem;vertical-align:middle}
.hidden{display:none!important}
.more{font-size:.78rem;color:var(--accent);cursor:pointer;margin-top:.2rem;user-select:none}
.compact .ex-item{font-size:.94rem} .compact ul.exlist{gap:.25rem}
.note{max-width:880px;margin:0 auto 1rem;padding:0 1rem;font-size:.8rem;color:var(--muted)}
</style></head>
<body>
<div class="bar">
  <b>sources</b>
  <span class="grp">
    <label><input type="checkbox" data-src="jitendex" checked> Jitendex</label>
    <label><input type="checkbox" data-src="saito" checked> 斎藤和英</label>
    <label><input type="checkbox" data-src="kenkyusha" checked> 研究社</label>
    <label><input type="checkbox" data-src="massif" checked> Massif</label>
    <label><input type="checkbox" data-src="ik" checked> Immersion&nbsp;Kit</label>
  </span>
  <b>show</b>
  <span class="grp">
    <label><input type="checkbox" id="opt-en" checked> EN</label>
    <label><input type="checkbox" id="opt-furi" checked> furigana</label>
    <label><input type="checkbox" id="opt-hl" checked> highlight</label>
    <label><input type="checkbox" id="opt-meta" checked> attribution</label>
  </span>
  <b>per src</b>
  <span class="grp">
    <label><input type="radio" name="cap" value="1"> 1</label>
    <label><input type="radio" name="cap" value="2" checked> 2</label>
    <label><input type="radio" name="cap" value="3"> 3</label>
    <label><input type="radio" name="cap" value="99"> all</label>
  </span>
  <b>layout</b>
  <span class="grp">
    <label><input type="checkbox" id="opt-cols"> 2-col</label>
    <label><input type="checkbox" id="opt-compact"> compact</label>
    <label><input type="checkbox" id="opt-open" checked> expanded</label>
    <label><input type="checkbox" id="opt-dark"> dark</label>
  </span>
</div>
<div class="note">10 real Kinomoto cards. Sources: <b>Jitendex</b> (J–E, Tatoeba, furigana — 68.8% card
coverage), <b>斎藤和英</b> (J–E curated — 54%), <b>研究社新英和</b> (E–J, reverse-indexed → JP
collocations), <b>Massif</b> (web-novel, scraped), <b>Immersion Kit</b> (anime; media = placeholder,
bucket blocks hotlinks). Toggle controls to compare designs. Rebuild: <code>python build_mockup.py</code></div>
<div class="wrap" id="wrap"></div>

<script>
const DATA = __DATA__;
const esc = s => (s||"").replace(/[&<>]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
function hl(text, term, reading){
  const A=String.fromCharCode(1), B=String.fromCharCode(2);
  let s = esc(text);
  for(const t of [term, reading]){ if(!t) continue;
    s = s.split(t).join(A+t+B); }
  return s.split(A).join('<mark>').split(B).join('</mark>');
}
const opt = id => document.getElementById(id).checked;

function render(){
  const wrap = document.getElementById('wrap'); wrap.innerHTML='';
  const srcOn = {}; document.querySelectorAll('[data-src]').forEach(c=>srcOn[c.dataset.src]=c.checked);
  const showEN=opt('opt-en'), showHL=opt('opt-hl'), showMeta=opt('opt-meta');
  const cap = parseInt(document.querySelector('input[name=cap]:checked').value);
  const open = opt('opt-open');
  document.body.classList.toggle('cols', opt('opt-cols'));
  document.body.classList.toggle('compact', opt('opt-compact'));
  document.body.classList.toggle('nofuri', !opt('opt-furi'));
  document.documentElement.classList.toggle('dark', opt('opt-dark'));

  for(const w of DATA){
    const card = document.createElement('div'); card.className='card';
    let h = `<div class="head"><span class="term">${esc(w.term)}</span>`+
            `<span class="read">${esc(w.reading)}</span>`+
            `<span class="freq">freq ${w.freq>1e8?'—':w.freq.toLocaleString()}</span></div>`;
    if(w.meaning) h += `<div class="meaning">${esc(w.meaning)}</div>`;
    if(w.sentence) h += `<div class="deck-sentence">${showHL?hl(w.sentence,w.term,w.reading):esc(w.sentence)}</div>`;
    h += `<details class="ex" ${open?'open':''}><summary>例文 · example sentences</summary>`;

    if(srcOn.jitendex && (w.jitendex||[]).length){
      h += srcBlock('jitendex','Jitendex（J–E）', w.jitendex.map(e=>
        `<li class="ex-item"><span class="jp">${e.jp_html}</span>`+
        (showEN&&e.en?`<span class="en">${esc(e.en)}</span>`:``)+`</li>`), cap);
    }
    if(srcOn.saito && (w.saito||[]).length){
      h += srcBlock('saito','斎藤和英（J–E）', w.saito.map(e=>
        `<li class="ex-item"><span class="jp">${showHL?hl(e.jp,w.term,w.reading):esc(e.jp)}</span>`+
        (showEN&&e.en?`<span class="en">${esc(e.en)}</span>`:``)+`</li>`), cap);
    }
    if(srcOn.kenkyusha && (w.kenkyusha||[]).length){
      h += srcBlock('kenkyusha','研究社（連語・E–J逆引き）', w.kenkyusha.map(e=>
        `<li class="ex-item"><span class="jp">${showHL?hl(e.jp,w.term,w.reading):esc(e.jp)}</span>`+
        (showEN&&e.en?`<span class="en">${esc(e.en)}</span>`:``)+`</li>`), cap);
    }
    if(srcOn.massif && (w.massif||[]).length){
      h += srcBlock('massif','Massif（小説）', w.massif.filter(e=>e.html).map(e=>
        `<li class="ex-item"><span class="jp">${e.html}</span>`+
        (showMeta&&e.src?`<span class="meta">— <a href="${esc(e.url)}" target="_blank">${esc(e.src)}</a>${e.date?' ('+e.date+')':''}</span>`:``)+`</li>`), cap);
    }
    if(srcOn.ik && (w.ik||[]).length){
      h += srcBlock('ik','Immersion Kit（アニメ）', w.ik.filter(e=>e.jp&&!e.jp.startsWith('[')).map(e=>
        `<li class="ex-item"><span class="jp">${showHL?hl(e.jp,w.term,w.reading):esc(e.jp)}</span>`+
        (showEN&&e.en?`<span class="en">${esc(e.en)}</span>`:``)+
        (showMeta?`<span class="meta">${esc(e.title)} <span class="ik-media">🖼 screenshot</span> <span class="ik-media">🔊 audio</span></span>`:``)+`</li>`), cap);
    }
    h += `</details>`;
    card.innerHTML = h; wrap.appendChild(card);
  }
  wrap.querySelectorAll('.more').forEach(m=>m.onclick=()=>{
    m.previousElementSibling.querySelectorAll('.hidden').forEach(x=>x.classList.remove('hidden'));
    m.remove();
  });
}
function srcBlock(cls,label,items,cap){
  if(!items.length) return '';
  const shown = items.map((x,i)=> i<cap ? x : x.replace('class="ex-item"','class="ex-item hidden"'));
  const extra = items.length-cap;
  return `<div class="src-block src-${cls}"><div class="src-h"><span class="dot"></span>${label} `+
    `<span style="opacity:.6;font-weight:400">· ${items.length}</span></div>`+
    `<ul class="exlist">${shown.join('')}</ul>`+
    (extra>0?`<div class="more">＋ show ${extra} more</div>`:``)+`</div>`;
}
document.querySelectorAll('.bar input').forEach(i=>i.addEventListener('change',render));
render();
</script>
</body></html>
"""

out = HTML.replace("__DATA__", json.dumps(data, ensure_ascii=False))
(ROOT / "mockup.html").write_text(out, encoding="utf-8")
print(f"wrote {ROOT/'mockup.html'}  ({len(out)} bytes, {len(data)} words, 5 sources)")
