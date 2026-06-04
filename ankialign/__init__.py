"""AnkiAlign add-on entry point: Tools menu action."""
try:
    from aqt import mw
    from aqt.qt import QAction
    from . import preview, audio_preview

    def _add_menu():
        unify = QAction("AnkiAlign: Unify note types…", mw)
        unify.triggered.connect(preview.show)
        mw.form.menuTools.addAction(unify)

        backfill = QAction("AnkiAlign: Backfill word audio…", mw)
        backfill.triggered.connect(audio_preview.show)
        mw.form.menuTools.addAction(backfill)

        repitch = QAction("AnkiAlign: Replace word audio (dictionary)…", mw)
        repitch.triggered.connect(audio_preview.show_repitch)
        mw.form.menuTools.addAction(repitch)

    _add_menu()
except ModuleNotFoundError as e:
    if not (e.name and e.name.split(".")[0] == "aqt"):
        raise  # a real missing internal module — don't hide it
    # else: running outside Anki (tests, linters)
