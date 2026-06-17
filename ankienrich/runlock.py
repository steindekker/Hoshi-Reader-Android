"""Non-blocking run-lock so a nightly run and a manual /run can never write the
single collection at once — whoever is second gets Busy and bows out."""
from __future__ import annotations

import contextlib
import fcntl

from . import paths


class Busy(Exception):
    """Another run already holds the lock."""


@contextlib.contextmanager
def acquire():
    paths.ensure_var()
    f = open(paths.RUN_LOCK, "w")
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
