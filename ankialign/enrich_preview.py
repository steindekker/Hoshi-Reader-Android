"""Dry-run dialog for definition enrichment; runs the pass on confirm."""
import os
from aqt import mw
from aqt.qt import QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout
from aqt.utils import showInfo
from anki.errors import AbortSchemaModification
from . import convert, enrich, config


def _plan_text(plan: dict) -> str:
    c = plan["counts"]
    lines = [f"Enrich (J-J): {c['enrich']}", f"English-only: {c['english']}",
             f"No definition: {c['nohit']}"]
    if c["nohit"]:
        verb = ("will be DROPPED" if c["nohit"] < config.NO_HIT_DROP_THRESHOLD
                else f"will be tagged {config.NO_DEF_TAG}")
        lines.append(f"   ({c['nohit']} no-hit cards {verb})")
    lines.append("")
    for term, old, new, source in plan["samples"][:config.SAMPLE_N]:
        lines.append(f"• {term}  [{source}]")
    if plan["errors"]:
        lines.append(f"\n⚠ {len(plan['errors'])} errors (skipped):")
        for nid, err in plan["errors"][:10]:
            lines.append(f"   nid {nid}: {err}")
    return "\n".join(lines)


def show() -> None:
    if not os.path.exists(config.DEF_INDEX_PATH):
        showInfo("No dictionary index found.\n\nBuild it first:\n"
                 "uv run --with anki python tools/build_def_index.py")
        return
    conn = enrich.open_index()
    plan = enrich.build_plan(mw.col, conn)

    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Enrich definitions (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); run = QPushButton("Backup && Enrich")
    btns.addWidget(cancel); btns.addWidget(run); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)
        try:
            mw.col.mod_schema(check=True)   # main-thread schema ack (adds fields)
        except AbortSchemaModification:
            conn.close(); showInfo("Cancelled — schema change not accepted."); return
        mw.progress.start(label="AnkiAlign: enriching definitions…", immediate=True)
        try:
            res = enrich.enrich_notes(mw.col, conn)
        finally:
            mw.progress.finish(); conn.close()
        mw.reset()
        showInfo(f"Enriched (written): {res['written']}\n"
                 f"Dropped (no def): {res['dropped']}\n"
                 f"Tagged no-def: {res['nohit_tagged']}\n\n"
                 f"Rollback: restore the backup at\n{backup_dir}")

    run.clicked.connect(do_run)
    dlg.exec()
