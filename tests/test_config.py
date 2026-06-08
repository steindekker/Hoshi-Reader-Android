import pytest
from ankialign import config


def test_in_scope_excludes_core_and_grammar():
    assert config.in_scope("Mining::Anime")
    assert not config.in_scope("Core2.3k::Vocab")
    assert not config.in_scope("文法")


def test_deck_exclusion_search():
    s = config.deck_exclusion_search()
    assert "-deck:*Core2.3k*" in s
    assert "-deck:*文法*" in s


def test_credentials_require_env(monkeypatch):
    monkeypatch.delenv("ANKIWEB_USERNAME", raising=False)
    monkeypatch.delenv("ANKIWEB_PASSWORD", raising=False)
    with pytest.raises(RuntimeError):
        config.ankiweb_credentials()
    monkeypatch.setenv("ANKIWEB_USERNAME", "u")
    monkeypatch.setenv("ANKIWEB_PASSWORD", "p")
    assert config.ankiweb_credentials() == ("u", "p")
