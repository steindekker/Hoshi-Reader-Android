# Coverage spike: term-audio backfill (goal #3)

Date: 2026-06-04. Read-only over `collection.anki2` (schema 18) + `entries.db` (590,410 rows).
Lookup chain: **nhk16 → shinmeikai8 → forvo[strawberrybrown] → jpod**, stop at first hit.

## Population

- jidoujisho Kinomoto notes (total): **8240**
- in scope (excl. Core2.3k/文法): **8240**
- of those, **empty `Term Audio`** (= backfill target N): **583** (7.1% of in-scope)
- target cards with **empty `Reading`**: **30** (5.1% of target)

## Coverage of the target N

| Outcome (winning source) | Cards | % of target |
|---|---:|---:|
| nhk16 | 280 | 48.0% |
| shinmeikai8 | 98 | 16.8% |
| forvo | 99 | 17.0% |
| jpod | 13 | 2.2% |
| nothing | 93 | 16.0% |
| **filled (any source)** | **490** | **84.0%** |

## Forvo tail detail

- forvo wins with a **strawberrybrown** clip: **27** (other speakers only: 72)
- forvo wins where **jpod also had an exact (term,reading) hit**: **9** — these are the cards where the forvo>jpod decision actually bites (homograph risk vs jpod's reading-safety).

Sample forvo-tail picks (term / reading / speaker):

- 筋トレ ／ きんとれ ／ strawberrybrown
- ラブラブ ／ らぶらぶ ／ skent
- 択 ／ たく ／ skent
- 振りをする ／ ふりをする ／ skent
- お凸 ／ おでこ ／ strawberrybrown
- 気を張る ／ きをはる ／ skent
- 力になる ／ ちからになる ／ strawberrybrown
- 今日中に ／ きょうちゅうに ／ skent
- 釘を刺す ／ くぎをさす ／ skent
- 身のため ／ みのため ／ strawberrybrown
- に越したことはない ／ にこしたことはない ／ skent
- 口をつぐむ ／ くちをつぐむ ／ poyotan
- お揃い ／ おそろい ／ kaoring
- その分 ／ そのぶん ／ skent
- 社畜 ／ しゃちく ／ strawberrybrown

## Residual misses (93 cards)

| Bucket | Cards |
|---|---:|
| term absent entirely | 70 |
| empty reading | 16 |
| term exists, reading mismatch | 7 |

Sample misses (term / reading):

- １対１ ／ １たい１
- 処か ／ ところか
- どん引き ／ どんびき
-  ／ —
- ムキになる ／ むきになる
- 前々から ／ まえまえから
- ドヤ顔 ／ どやがお
- と言い ／ といい
- きょとん ／ きょとん
- ぶつけ合う ／ ぶつけあう
- リア充 ／ りあじゅう
- 徒ならぬ ／ とならぬ
- ピンと来る ／ ぴんとくる
- 見つめ直す ／ みつめなおす
- ゴチャゴチャ ／ ごちゃごちゃ
- 傍から見る ／ はたからみる
- 混ざり合う ／ まざりあう
- 眉根を寄せる ／ まゆねをよせる
- ずじまい ／ ずじまい
- 畝り ／ うねり
- 一押し ／ —
- 戯れ合う ／ じゃれあう
- 魔法陣 ／ まほうじん
- でれる ／ でれる
- 獣人 ／ じゅうじん

---

## Re-pitch existing audio (separate pass — `upgrade_spike.py`)

Motivation: existing Term Audio is almost all jidoujisho-mined JapanesePod101, which
has frequent wrong pitch. **Cannot identify jpod files forensically** — filenames are
opaque jidoujisho timestamps (`1635235872.58.mp3`), clips (~0.9s) are too short for
Chromaprint ("Empty fingerprint"), and card-vs-jpod durations are inconclusive
(median |Δ| 0.11s but ±0.67s spread; local-audio trims silence). Doesn't matter:
wherever an accent dictionary has a reading-correct recording, it is authoritative.

Policy (decided): replace existing Term Audio **only** where nhk16 or shinmeikai8 has
an exact (term, reading) hit (nhk16 preferred). Never replace with forvo/jpod.

| Outcome | Cards | % of audio'd |
|---|---:|---:|
| **REPLACE** (nhk16/shinmeikai8 reading-hit) | **7185** | **93.9%** |
| — nhk16 has it | 6164 | |
| — shinmeikai8 has it | 6855 | |
| leave: forvo-only (no dict) | 290 | 3.8% |
| leave: no authoritative source | 180 | 2.4% |

(of 7,655 in-scope Kinomoto cards with Term Audio; 68 have empty Reading.)
