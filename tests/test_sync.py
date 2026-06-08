from ankialign import sync


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
