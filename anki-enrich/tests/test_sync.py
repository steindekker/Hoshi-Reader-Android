from types import SimpleNamespace

import pytest
from anki.sync import SyncAuth, SyncOutput

from ankienrich import sync


class _FakeCol:
    """Returns each queued media_sync_status() in turn; raising entries are raised."""

    def __init__(self, statuses):
        self._statuses = list(statuses)
        self.polls = 0

    def media_sync_status(self):
        self.polls += 1
        s = self._statuses.pop(0)
        if isinstance(s, Exception):
            raise s
        return SimpleNamespace(active=s)


def test_await_media_sync_blocks_until_inactive():
    # sync.sync_media() only kicks off the transfer; the wait must keep polling
    # while it's active and return only once the backend reports it finished.
    col = _FakeCol([True, True, False])
    sync._await_media_sync(col, poll=0)
    assert col.polls == 3


def test_await_media_sync_propagates_backend_error():
    # media_sync_status() throws if the sync failed; that must surface, not hang.
    col = _FakeCol([True, RuntimeError("media sync failed")])
    with pytest.raises(RuntimeError, match="media sync failed"):
        sync._await_media_sync(col, poll=0)


def test_endpoint_for_honors_redirect():
    auth = SyncAuth(hkey="KEY", endpoint="https://sync.ankiweb.net/")
    redirected = sync.endpoint_for(auth, SyncOutput(new_endpoint="https://sync7.ankiweb.net/"))
    assert redirected.endpoint == "https://sync7.ankiweb.net/"
    assert redirected.hkey == "KEY"
    # no redirect -> auth returned unchanged
    same = sync.endpoint_for(auth, SyncOutput())
    assert same.endpoint == "https://sync.ankiweb.net/"


def test_resolve_pull_actions():
    R = sync.Required
    assert sync.resolve_pull(R.NO_CHANGES) == "none"
    assert sync.resolve_pull(R.NORMAL_SYNC) == "normal"
    assert sync.resolve_pull(R.FULL_DOWNLOAD) == "download"
    # On pull, a server that wants a full upload means our local copy is ahead;
    # for a fresh working copy we still treat FULL_SYNC as "download".
    assert sync.resolve_pull(R.FULL_SYNC) == "download"


def test_resolve_push_actions():
    R = sync.Required
    assert sync.resolve_push(R.NO_CHANGES) == "none"
    assert sync.resolve_push(R.NORMAL_SYNC) == "normal"
    # Schema change from enrichment -> server requires a full upload.
    assert sync.resolve_push(R.FULL_SYNC) == "upload"
    assert sync.resolve_push(R.FULL_UPLOAD) == "upload"
