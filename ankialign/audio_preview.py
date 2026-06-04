"""Read-only dry-run dialog for the term-audio backfill, then runs it on confirm."""
import sqlite3

from aqt import mw
from aqt.qt import (QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout)
from aqt.utils import showInfo

from . import audio, convert


def _open_db(db_path: str):
    # read-only; won't create or lock against the local-audio add-on's own handle
    return sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)


def _plan_text(plan: dict) -> str:
    lines = [
        f"Cards missing Term Audio (in scope): {plan['total']}",
        f"  → will fill: {plan['filled']}",
    ]
    for src in ("nhk16", "shinmeikai8", "forvo", "jpod"):
        if plan["by_source"].get(src):
            lines.append(f"      {src}: {plan['by_source'][src]}")
    lines.append(f"  → no audio found (left empty): {plan['nothing']}")
    if plan["missing_file"]:
        lines.append(f"  → index hit but file missing on disk: {plan['missing_file']}")
    lines.append("\nSamples:")
    for term, reading, src, speaker in plan["samples"]:
        tag = f"{src}/{speaker}" if speaker else src
        lines.append(f"   • {term}  [{reading}]  ←  {tag}")
    return "\n".join(lines)


def show() -> None:
    located = audio.locate(mw.addonManager.addonsFolder())
    if located is None:
        showInfo(
            "local-audio-yomichan (add-on code 1045800357) and its entries.db were "
            "not found.\n\nInstall the add-on, download an audio pack into its "
            "user_files/, and relaunch Anki so it builds the index — then try again.")
        return
    db_path, user_files_dir = located
    conn = _open_db(db_path)
    try:
        plan = audio.plan_backfill(mw.col, conn, user_files_dir)
    finally:
        conn.close()

    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Backfill word audio (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel("Dry run — nothing has changed yet:"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_plan_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel")
    run_btn = QPushButton("Backup && Fill")
    run_btn.setEnabled(plan["filled"] > 0)
    btns.addWidget(cancel); btns.addWidget(run_btn); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)  # synchronous, verified

        conn2 = _open_db(db_path)
        mw.progress.start(label="AnkiAlign: backfilling word audio…", immediate=True)
        try:
            res = audio.run_backfill(mw.col, conn2, user_files_dir)
        finally:
            mw.progress.finish()
            conn2.close()
        mw.reset()

        filled = res["filled"]
        line = ", ".join(f"{k} {v}" for k, v in filled.items()) or "none"
        total = sum(filled.values())
        errs = res.get("errors", [])
        err_line = f"\n⚠ {len(errs)} card(s) errored and were skipped." if errs else ""
        showInfo(
            f"Filled Term Audio on {total} card(s): {line}{err_line}\n\n"
            f"Rollback: restore the backup at\n{backup_dir}\n"
            "(or Ctrl+Z to undo the last note edits).")

    run_btn.clicked.connect(do_run)
    dlg.exec()


def _repitch_text(plan: dict) -> str:
    lines = [
        f"Cards with non-opus (mined) Term Audio in scope: {plan['targets']}",
        f"  → will REPLACE with accent-dictionary audio: {plan['replaceable']}",
    ]
    for src in ("nhk16", "shinmeikai8"):
        if plan["by_source"].get(src):
            lines.append(f"      {src}: {plan['by_source'][src]}")
    lines.append(f"  → no dictionary recording (left as-is): {plan['no_dict']}")
    lines.append("\nSamples (old file  →  new source):")
    for term, reading, old, src in plan["samples"]:
        lines.append(f"   • {term}  [{reading}]   {old}  →  {src}")
    return "\n".join(lines)


def show_repitch() -> None:
    located = audio.locate(mw.addonManager.addonsFolder())
    if located is None:
        showInfo(
            "local-audio-yomichan (add-on code 1045800357) and its entries.db were "
            "not found.\n\nInstall the add-on, download an audio pack into its "
            "user_files/, and relaunch Anki so it builds the index — then try again.")
        return
    db_path, user_files_dir = located
    conn = _open_db(db_path)
    try:
        plan = audio.plan_repitch(mw.col, conn, user_files_dir)
    finally:
        conn.close()

    dlg = QDialog(mw)
    dlg.setWindowTitle("AnkiAlign — Replace word audio with dictionary (preview)")
    lay = QVBoxLayout(dlg)
    lay.addWidget(QLabel(
        "Dry run — nothing has changed yet. Replaces mined word audio with NHK/"
        "Shinmeikai recordings where available (the old file is kept in your "
        "collection, only the card's reference changes):"))
    box = QTextEdit(); box.setReadOnly(True); box.setPlainText(_repitch_text(plan))
    lay.addWidget(box)
    btns = QHBoxLayout()
    cancel = QPushButton("Cancel")
    run_btn = QPushButton("Backup && Replace")
    run_btn.setEnabled(plan["replaceable"] > 0)
    btns.addWidget(cancel); btns.addWidget(run_btn); lay.addLayout(btns)
    cancel.clicked.connect(dlg.reject)

    def do_run():
        dlg.accept()
        backup_dir = mw.pm.backupFolder()
        convert.backup_collection(mw.col, backup_dir)

        conn2 = _open_db(db_path)
        mw.progress.start(label="AnkiAlign: replacing word audio…", immediate=True)
        try:
            res = audio.run_repitch(mw.col, conn2, user_files_dir)
        finally:
            mw.progress.finish()
            conn2.close()
        mw.reset()

        replaced = res["replaced"]
        line = ", ".join(f"{k} {v}" for k, v in replaced.items()) or "none"
        total = sum(replaced.values())
        errs = res.get("errors", [])
        err_line = f"\n⚠ {len(errs)} card(s) errored and were skipped." if errs else ""
        showInfo(
            f"Replaced Term Audio on {total} card(s): {line}{err_line}\n\n"
            f"Old audio files are still in your collection (now unused — Tools → "
            f"Check Media will list them).\n"
            f"Rollback: restore the backup at\n{backup_dir}")

    run_btn.clicked.connect(do_run)
    dlg.exec()
