from ankienrich import bot


def test_spawn_argv_apply():
    assert bot._spawn_argv(overwrite=False) == [
        "anki-enrich", "--apply", "--notify"]


def test_spawn_argv_overwrite():
    assert bot._spawn_argv(overwrite=True) == [
        "anki-enrich", "--apply", "--overwrite", "--notify"]


def test_authorized():
    assert bot._authorized(42, 42)
    assert not bot._authorized(99, 42)


def test_run_keyboard_has_three_actions():
    kb = bot._run_keyboard()
    datas = [b.callback_data for row in kb.keyboard for b in row]
    assert datas == ["run:apply", "run:overwrite", "run:cancel"]


def test_dispatch_spawns_on_apply(monkeypatch):
    spawned = {}
    monkeypatch.setattr(bot.subprocess, "Popen",
                        lambda argv, **kw: spawned.setdefault("argv", argv))
    bot._dispatch("run:overwrite")
    assert spawned["argv"] == ["anki-enrich", "--apply", "--overwrite", "--notify"]


def test_dispatch_cancel_does_not_spawn(monkeypatch):
    spawned = []
    monkeypatch.setattr(bot.subprocess, "Popen",
                        lambda argv, **kw: spawned.append(argv))
    assert bot._dispatch("run:cancel") is None
    assert spawned == []
