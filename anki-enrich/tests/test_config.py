import pytest
from ankienrich import config


def test_credentials_require_env(monkeypatch):
    monkeypatch.delenv("ANKIWEB_USERNAME", raising=False)
    monkeypatch.delenv("ANKIWEB_PASSWORD", raising=False)
    with pytest.raises(RuntimeError):
        config.ankiweb_credentials()
    monkeypatch.setenv("ANKIWEB_USERNAME", "u")
    monkeypatch.setenv("ANKIWEB_PASSWORD", "p")
    assert config.ankiweb_credentials() == ("u", "p")
