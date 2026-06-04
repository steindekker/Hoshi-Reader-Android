"""Read-only dry-run dialog: shows the plan, then runs the conversion on confirm."""
from aqt import mw
from aqt.qt import (QDialog, QVBoxLayout, QLabel, QTextEdit, QPushButton, QHBoxLayout)
from aqt.utils import showInfo
from aqt.operations import CollectionOp
from . import convert, template


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

        def op(col):
            res = convert.run_conversion(col, backup_dir=backup_dir, make_backup=False)
            template.apply(col)
            return res

        def done(res):
            c = res["converted"]
            showInfo(
                "Converted: " + ", ".join(f"{k} {v}" for k, v in c.items())
                + f"\n\nRollback: restore the backup at\n{backup_dir}\n"
                + "(Ctrl+Z undoes only the last step, not the whole run.)")

        CollectionOp(parent=mw, op=op).success(done).run_in_background()

    convert_btn.clicked.connect(do_convert)
    dlg.exec()
