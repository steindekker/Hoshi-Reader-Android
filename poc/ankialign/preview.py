"""Read-only dry-run dialog: shows the plan, then runs the conversion on confirm."""
from aqt import mw
from aqt.qt import (QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout)
from aqt.utils import showInfo
from anki.errors import AbortSchemaModification
from . import convert, template, pitch


def _plan_text(plan: dict) -> str:
    lines = []
    for name, info in plan["sources"].items():
        lines.append(f"{name}: {info['count']} notes")
        for src, kino in info["samples"][:3]:
            lines.append(f"   • {kino.get('Term','')}  ←  "
                         f"{src.get('Target Word', src.get('Term', src.get('EntryFurigana','')))}")
    if plan["errors"]:
        lines.append(f"\n⚠ {len(plan['errors'])} mapping errors (will be skipped):")
        for nid, err in plan["errors"][:10]:
            lines.append(f"   nid {nid}: {err}")
    return "\n".join(lines) or "No in-scope notes found."


def show() -> None:
    plan = convert.build_plan(mw.col)
    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Unify note types (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); convert_btn = QPushButton("Backup && Convert")
    btns.addWidget(cancel); btns.addWidget(convert_btn); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_convert():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        # Back up synchronously, before the background op, and verify it landed.
        convert.backup_collection(mw.col, backup_dir)

        # col.models.change() calls mod_schema(check=True), which fires the
        # schema_will_change hook — and that hook must run on the MAIN thread
        # (it prompts about the full sync). CollectionOp runs op() on a worker
        # thread, so acknowledge the schema change here first. Anki only fires
        # the hook the first time the schema changes this session, so the
        # change() calls inside the op then skip it.
        try:
            mw.col.mod_schema(check=True)
        except AbortSchemaModification:
            showInfo("Cancelled — schema change was not accepted.")
            return

        # Run on the main thread (schema-modifying bulk op): a progress dialog
        # keeps the UI responsive-looking; CollectionOp's worker thread can't
        # run the schema hook and expects a changes object we don't produce.
        mw.progress.start(label="AnkiAlign: converting notes…", immediate=True)
        try:
            # convert any in-scope source notes still present (none on a re-run)
            if any(convert.select_notes(mw.col).values()):
                res = convert.run_conversion(mw.col, backup_dir=backup_dir, make_backup=False)
            else:
                res = {"converted": {}}
            # repair bare audio/image on all Kinomoto notes (fixes Yuuna-origin
            # cards from an earlier run) + (re)apply styling/front fix
            fixed = convert.normalize_media(mw.col)
            template.apply(mw.col)
        finally:
            mw.progress.finish()
        mw.reset()  # refresh browser/reviewer to show the converted notes

        c = res["converted"]
        converted_line = ", ".join(f"{k} {v}" for k, v in c.items()) or "none (nothing new)"
        showInfo(
            f"Converted: {converted_line}\n"
            f"Media fields normalized: {fixed}\n\n"
            f"Rollback: restore the backup at\n{backup_dir}\n"
            "(Ctrl+Z undoes only the last step, not the whole run.)")

    convert_btn.clicked.connect(do_convert)
    dlg.exec()


def _pitch_plan_text(plan: dict) -> str:
    c = plan["counts"]
    lines = [
        f"Will fill:   {c['filled']} notes",
        f"Not in dict: {c['not_found']} (left empty)",
        f"No reading:  {c['skipped']} (skipped)",
        "",
        "Samples (term — reading — accent graph):",
    ]
    for term, reading, _svg in plan["samples"]:
        lines.append(f"   • {term}  —  {reading}")
    return "\n".join(lines)


def show_pitch() -> None:
    plan = pitch.build_pitch_plan(mw.col)
    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Backfill pitch graphs (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_pitch_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel"); run = QPushButton("Backup && Backfill")
    btns.addWidget(cancel); btns.addWidget(run); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)   # forced, verified backup
        mw.progress.start(label="AnkiAlign: backfilling pitch graphs…", immediate=True)
        try:
            res = pitch.backfill_pitch(mw.col)
        finally:
            mw.progress.finish()
        mw.reset()
        showInfo(
            f"Filled: {res['filled']}\n"
            f"Not in dict: {res['not_found']}\n"
            f"Skipped (no reading): {res['skipped']}\n\n"
            f"Rollback: restore the backup at\n{backup_dir}")

    run.clicked.connect(do_run)
    dlg.exec()
