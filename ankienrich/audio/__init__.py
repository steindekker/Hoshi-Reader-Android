"""Term-audio enrichment package. Runtime lives in `enrich`; the self-contained
store is built by `build` and opened by `index`."""
from .enrich import (
    DICT_SOURCES, FILL_ORDER, FORVO_SPEAKER, SOURCE_LABELS, Pick,
    lookup, lookup_dict, read_clip,
    plan_backfill, run_backfill, select_targets,
    plan_repitch, run_repitch, select_repitch_targets,
)

__all__ = [
    "DICT_SOURCES", "FILL_ORDER", "FORVO_SPEAKER", "SOURCE_LABELS", "Pick",
    "lookup", "lookup_dict", "read_clip",
    "plan_backfill", "run_backfill", "select_targets",
    "plan_repitch", "run_repitch", "select_repitch_targets",
]
