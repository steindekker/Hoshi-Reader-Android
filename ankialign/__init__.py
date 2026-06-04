"""AnkiAlign add-on entry point: Tools menu action."""
try:
    from aqt import mw
    from aqt.qt import QAction
    from . import preview

    def _add_menu():
        action = QAction("AnkiAlign: Unify note types…", mw)
        action.triggered.connect(preview.show)
        mw.form.menuTools.addAction(action)

    _add_menu()
except ModuleNotFoundError as e:
    if not (e.name and e.name.split(".")[0] == "aqt"):
        raise  # a real missing internal module — don't hide it
    # else: running outside Anki (tests, linters)
