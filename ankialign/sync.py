"""Headless AnkiWeb sync for the working-copy collection.

pull() brings the latest down (start of every run, dry-run included). push()
uploads after --apply; enrichment adds fields, so a full upload is the common
outcome. A full transfer mirrors Anki's own sequence: honor the server-assigned
endpoint, close_for_full_sync, full_upload_or_download, then reopen — the
collection object is reopened in place, so callers keep using the same handle.
pull/push return (action, auth); auth may carry an updated endpoint.
"""
from anki.collection import Collection
from anki.sync import SyncAuth, SyncOutput

from . import config

# Protobuf EnumTypeWrapper — members are plain ints (NO_CHANGES=0, NORMAL_SYNC=1,
# FULL_SYNC=2, FULL_DOWNLOAD=3, FULL_UPLOAD=4).
Required = SyncOutput.ChangesRequired


def login(col: Collection) -> SyncAuth:
    user, pw = config.ankiweb_credentials()
    return col.sync_login(user, pw, None)


def resolve_pull(required) -> str:
    if required == Required.NO_CHANGES:
        return "none"
    if required == Required.NORMAL_SYNC:
        return "normal"
    # Any full requirement on pull -> download the server copy (our working copy
    # is disposable; the server is the source of truth at the start of a run).
    return "download"


def resolve_push(required) -> str:
    if required == Required.NO_CHANGES:
        return "none"
    if required == Required.NORMAL_SYNC:
        return "normal"
    # FULL_SYNC / FULL_UPLOAD -> upload our enriched copy.
    return "upload"


def endpoint_for(auth: SyncAuth, out: SyncOutput) -> SyncAuth:
    """AnkiWeb hands a normal sync a regional endpoint; the full transfer and
    later media sync must target it, else the server 400s. Returns auth
    unchanged when no redirect was given."""
    if out.new_endpoint:
        return SyncAuth(hkey=auth.hkey, endpoint=out.new_endpoint)
    return auth


def _full_transfer(col: Collection, auth: SyncAuth, out: SyncOutput, *, upload: bool) -> None:
    col.close_for_full_sync()
    col.full_upload_or_download(auth=auth, server_usn=out.server_media_usn, upload=upload)
    col.reopen(after_full_sync=True)


def pull(col: Collection, auth: SyncAuth) -> tuple[str, SyncAuth]:
    out = col.sync_collection(auth, sync_media=True)
    auth = endpoint_for(auth, out)
    action = resolve_pull(out.required)
    if action == "download":
        _full_transfer(col, auth, out, upload=False)
    col.sync_media(auth)
    return action, auth


def push(col: Collection, auth: SyncAuth) -> tuple[str, SyncAuth]:
    out = col.sync_collection(auth, sync_media=True)
    auth = endpoint_for(auth, out)
    action = resolve_push(out.required)
    if action == "upload":
        _full_transfer(col, auth, out, upload=True)
    col.sync_media(auth)
    return action, auth
