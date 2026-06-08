"""Headless AnkiWeb sync for the working-copy collection.

pull() brings the latest down (used at the start of every run, dry-run included).
push() uploads after --apply; enrichment adds fields, so a full upload is the
common outcome. Credentials come from the environment (config)."""
from anki.collection import Collection
from anki.sync import SyncOutput

from . import config

# Protobuf EnumTypeWrapper — members are plain ints (NO_CHANGES=0, NORMAL_SYNC=1,
# FULL_SYNC=2, FULL_DOWNLOAD=3, FULL_UPLOAD=4).
Required = SyncOutput.ChangesRequired


def login(col: Collection):
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


def pull(col: Collection, auth) -> str:
    out = col.sync_collection(auth, sync_media=True)
    action = resolve_pull(out.required)
    if action == "download":
        # SyncOutput has no server_usn; pass None (server resolves USN).
        col.full_upload_or_download(auth=auth, server_usn=None, upload=False)
    col.sync_media(auth)
    return action


def push(col: Collection, auth) -> str:
    out = col.sync_collection(auth, sync_media=True)
    action = resolve_push(out.required)
    if action == "upload":
        # SyncOutput has no server_usn; pass None (server resolves USN).
        col.full_upload_or_download(auth=auth, server_usn=None, upload=True)
    col.sync_media(auth)
    return action
