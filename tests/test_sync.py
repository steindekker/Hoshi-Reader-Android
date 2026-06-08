from anki.sync import SyncAuth, SyncOutput

from ankialign import sync


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
