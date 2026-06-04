"""AnkiAlign add-on entry point: Tools menu action."""
try:
    from aqt import mw
    from aqt.qt import QAction
    from . import preview, enrich_preview

    def _add_menu():
        a1 = QAction("AnkiAlign: Unify note types…", mw)
        a1.triggered.connect(preview.show)
        mw.form.menuTools.addAction(a1)
        a2 = QAction("AnkiAlign: Enrich definitions…", mw)
        a2.triggered.connect(enrich_preview.show)
        mw.form.menuTools.addAction(a2)

    _add_menu()
except ModuleNotFoundError as e:
    if not (e.name and e.name.split(".")[0] == "aqt"):
        raise  # a real missing internal module — don't hide it
    # else: running outside Anki (tests, linters)
