"""Example-sentence enrichment: an expandable 例文 block on Kinomoto cards.

Up to config.EXAMPLE_CAP sentences per card, mined from owned Yomitan dicts
(Jitendex / 斎藤 / 用例.jp) plus the Tatoeba dump, filled in priority order with
cross-source dedupe. Mirrors the defs/ package layout: build -> index -> enrich,
with pure parsing/rendering in parse/render."""
