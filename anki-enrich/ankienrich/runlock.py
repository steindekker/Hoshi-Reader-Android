"""Non-blocking run-lock so a nightly run and a manual /run (or `add --apply`)
can never write the single collection at once — whoever is second gets Busy
and bows out."""
from __future__ import annotations

import contextlib
import fcntl
import os

from . import paths


class Busy(Exception):
    """Another run already holds the lock."""


def _open_lock(path: str):
    """Open lock file for flock.

    Docker (root) may own run.lock as root:root 644, so write opens fail for the
    host user — read-only + LOCK_EX still works on local Linux filesystems.
    """
    try:
        return open(path, "r+")
    except FileNotFoundError:
        paths.ensure_var()
        return open(path, "a+")
    except PermissionError:
        return open(path, "r")


@contextlib.contextmanager
def acquire():
    path = paths.run_lock_path()
    f = _open_lock(path)
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        f.close()
        raise Busy()
    try:
        yield
    finally:
        fcntl.flock(f, fcntl.LOCK_UN)
        f.close()


def is_held() -> bool:
    """True if another process currently holds an exclusive flock on run.lock."""
    path = paths.run_lock_path()
    if not os.path.exists(path):
        return False
    try:
        f = _open_lock(path)
    except OSError:
        return False
    try:
        fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return True
    else:
        fcntl.flock(f, fcntl.LOCK_UN)
        return False
    finally:
        f.close()
