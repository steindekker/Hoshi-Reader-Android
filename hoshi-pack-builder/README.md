# hoshi-pack-builder

Builds the Hoshi enrichment pack (`hoshi-pack.sqlite` + `pack.json`) straight from the Yomitan zips. It replaces `anki-enrich pack` and the index builds before it (`defs`, `examples`, `freq`). There is no venv, nothing gets unpacked, and no intermediate sqlite files are written. The output is byte-identical to the Python builder's: every row blob is the same, so the pack `version` is the same.

```bash
cargo build --release
./target/release/hoshi-pack-builder build \
  --dicts <dir-of-yomitan-zips> \
  --jmdict <url-or-path-to>/jmdict-english.zip \
  --tatoeba ../anki-enrich/dicts/tatoeba \
  --accent ../anki-enrich/ankienrich/data/accent_index.marshal.gz \
  --sudachi-dic ../anki-enrich/.venv/lib/python3.12/site-packages/sudachidict_full/resources/system.dic \
  --out <out-dir>
```

- `--dicts DIR` scans every zip in DIR and identifies each one by its `index.json` title (三省堂国語辞典　第八版, 広辞苑 第七版, 実用日本語表現辞典, 大辞泉 第二版, JMdict…, Jitendex…, NEW斎藤和英大辞典, 用例.jp, JPDB…). `--dict ID=PATH` names a zip explicitly and wins over `--dicts`. A missing dictionary drops its fields, as it does in anki-enrich.
- `--jmdict` takes a path or a URL. `--token-file` sends `X-Hoshi-Token` with the URL.
- `--sudachi-dic` also builds the sentence model (`jrp-accent.sqlite` plus a copy of `sudachi-system.dic`) and writes the manifest's `sentence` block (`sentence_model.py`). The label (`sudachidict_full 20260428`) comes from the `dist-info` next to an installed `sudachidict_full`, or from `--sudachi-source`. Without `--sudachi-dic`, `pack.json` has no `sentence` block. That is the same manifest Python writes when sudachidict is not installed, and `publish.py` builds the sentence model itself in that case anyway.
- `--threads N` sets the number of worker threads. `--stream-over-mb N`: banks bigger than this are parsed while they inflate instead of from an inflated buffer, which bounds memory but parses them on one thread. The default is 64 with up to 4 threads and 512 above that.
- `HOSHI_PACK_PROFILE=1` prints phase and per-bank timings to stderr.
- `--compare PACK` diffs the result against another pack after the build and exits non-zero on any difference.

## Parity check

```bash
./target/release/hoshi-pack-builder compare NEW/hoshi-pack.sqlite OLD/   # file or its directory
```

This decodes every row of both packs with their own zdict and reports:

- meta keys that differ (`built_at` always does)
- rows present in only one pack
- whether row order matches
- per `ae-*` key, how many rows differ, with the first differing character shown in context

It exits 1 on any difference.

## Pack format 1

The format is what `anki-enrich/ankienrich/pack.py` writes. The app decodes it in `EnrichPack.kt` / `SqliteEnrichPackSource`.

- `meta(key TEXT PRIMARY KEY, value BLOB NOT NULL)`, rows inserted in this order:
  - `format`: `"1"`
  - `zdict`: BLOB
  - `defaults`: `{"ae-definition": "{glossary-first}"}`
  - `fallback_keys`: `["ae-definition", "ae-definition-source", "ae-examples", "ae-tags"]`
  - `version`
  - `built_at`: `YYYY-MM-DDTHH:MM:SS+00:00`
  - `entries`
  - `keys`: the `ae-*` keys any row carries, in `KEY_ORDER`

  All values except `zdict` are TEXT.
- `entries(term TEXT NOT NULL, reading TEXT NOT NULL, data BLOB NOT NULL)` plus `CREATE INDEX entries_term ON entries (term)`. Pragmas are `page_size = 4096` and `journal_mode = OFF`, and the whole build runs in one transaction.
- Rows: the distinct `(term, reading or term)` of the definition rows, in definition-chain order: 三省堂, 広辞苑, 実用, 大辞泉 by bank name (`term_bank_1, term_bank_10, …`) and entry order, then JMdict. Headwords with no definition (`nohit`) are skipped.
- `data` is the JSON for `{key: value}` in `KEY_ORDER` (`ae-definition`, `-definition-source`, `-pitch`, `-examples`, `-frequency`, `-tags`), with empty values dropped. It is serialized as `json.dumps(ensure_ascii=False, separators=(",", ":"))` and compressed with `compressobj(9, DEFLATED, 15, memLevel 9, Z_DEFAULT_STRATEGY, zdict)` (zlib 1.3.2, the release CPython bundles).
- `zdict` is trained on one headword in 50, sampled with `random.Random(1).randrange(50) == 0` over the rows. Count every 64-byte chunk at a stride of 16 and keep chunks seen more than 3 times, most common first (ties in first-seen order). Take chunks up to 32 KiB, then concatenate them in reverse.
- `version` is the first 16 hex characters of SHA-256 over `format`, `zdict`, `defaults` and `fallback_keys`, then `term\0reading\0blob` for each row.
- `pack.json`:

  ```json
  {"format": 1, "version": "…", "file": "hoshi-pack.sqlite", "size": …, "sha256": "…", "built_at": "…", "entries": …, "sentence": {…}}
  ```

  It is pretty-printed with 2 spaces and ends with a newline. `sentence` is optional.

The field values follow anki-enrich exactly. Each module notes the Python function it ports.

- `defs.rs`: structured content rendered to HTML, JMdict glosses, `choose_jj`, `numbered_glosses`. Ties are broken the way the `(term, reading)` sqlite index orders rows: by reading bytes, then rowid.
- `examples.rs`: Jitendex, 斎藤 and 用例.jp extraction, the Tatoeba token index, and priority fill with dedupe.
- `freq.rs`: the JPDB rank.
- `accent.rs`: a CPython marshal reader for the accent index, `accents_for`, and the pitch SVG.
- `py.rs`: the CPython behaviors the output depends on: `html.escape`, `str.isspace`, `str(value)`, `:g`, jaconv's kana tables, and the Mersenne Twister.

## Memory and speed

The setup server has 2 cores and about 1 GB of RAM free.

Loading:
- Bank JSON is deserialized one entry at a time into a bump arena whose strings borrow from the inflated bank. Each zip's central directory is parsed once.
- Banks over 48 MB (広辞苑, 用例.jp) are cut at top-level element boundaries and their pieces parsed in parallel.
- Strings are interned in one arena.
- Rendered definition HTML and example text go to a temp file in `--out` (about 740 MB, deleted when the build finishes) and are read back through mmap.
- 大辞泉 rows for a term that an earlier chain dictionary already has can never be chosen, so they are neither rendered nor stored; the builder only checks whether their HTML would be non-empty. 大辞泉 starts as soon as the three earlier chain dictionaries are done.

Compressing:
- The row deflater is primed with the zdict once, and each row starts from a `deflateCopy` of that state into pooled buffers. That is the state `deflateReset` + `deflateSetDictionary` produce, so the bytes are the same, without re-hashing 32 KB of dictionary per row.
- Rows whose JSON repeats within a chunk (about 133k, mostly a term's readings without a pitch graph) are compressed once.
- Workers take 2,048-headword chunks off an ordered queue, with no barrier between chunks. The writer thread puts the chunks back in order, inserts and hashes them, and lets workers run at most a few chunks ahead.
- The sentence model is written while the pack is being compressed.
- SHA-256 runs through ring: about 570 MB/s here, versus 300 MB/s for the portable code on a CPU without SHA extensions.

Measured on 2026-10-16 (i9-10900K, 10 cores / 20 threads) with the same inputs as the reference pack, sentence model included, median of three runs. Every run produced byte-identical rows and version `40a2f699e2afdd56`.

| | wall | peak anonymous RSS |
|---|---|---|
| Python: defs + examples + freq index, then `pack.build` | 281 s (99 + 21 + 1 + 160) | – |
| `hoshi-pack-builder`, default threads (20) | 7.4 s | 1.4 GB |
| `hoshi-pack-builder --threads 2` (server-like) | 35.6 s | 390 MB (at most 415 MB) |

At 2 threads, deflate is the floor. Compressing the 718k distinct rows at level 9 with the same zlib takes 43 thread-seconds, about 22 s on 2 cores, because output must stay byte-identical. Loading takes 8.5 s. The 2-thread build is about 36 s:

| phase | 2 threads | 20 threads |
|---|---|---|
| discover | 0.2 s | 0.2 s |
| load | 8.5 s | 1.9 s |
| zdict | 0.5 s | 0.1 s |
| pack (+ index) | 26 s | 4.5 s |
| manifest | 0.7 s | 0.7 s |

On the server, plan for about 2 GB of free disk during a build:

- the zips: about 780 MB
- the temp store: about 740 MB
- the output: 380 MB, plus 390 MB with the sentence model

## Deploying

The binary needs glibc 2.34 at most. zlib 1.3.2 and sqlite are linked statically.

`hoshi-pack.sqlite` differs from the Python output only in the sqlite library version in the header (byte 100) and the `built_at` text. That is why `pack.json`'s `sha256` differs. The same applies to `jrp-accent.sqlite`.

```bash
scp target/release/hoshi-pack-builder <server>:.local/bin/
```
