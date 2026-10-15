#!/usr/bin/env python3
"""Serve a directory over HTTP with single-range responses.

The setup client resumes a download with `Range: bytes=N-`. Python's
stdlib server ignores Range, so this handler answers those with 206.
"""
from __future__ import annotations

import argparse
import os
import re
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

FORBIDDEN = ("/opt/anki-enrich", "/data/apps/anki-enrich")
RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)\Z", re.IGNORECASE)


class _LimitedReader:
    def __init__(self, handle, remaining: int):
        self._handle = handle
        self._remaining = remaining

    def read(self, size: int = -1) -> bytes:
        if self._remaining <= 0:
            return b""
        if size is None or size < 0 or size > self._remaining:
            size = self._remaining
        data = self._handle.read(size)
        self._remaining -= len(data)
        return data

    def close(self) -> None:
        self._handle.close()


def forbidden(path: str) -> bool:
    normalized = path.replace("\\", "/")
    absolute = os.path.abspath(path).replace("\\", "/")
    return any(snippet in text for text in (normalized, absolute) for snippet in FORBIDDEN)


class RangeRequestHandler(SimpleHTTPRequestHandler):
    server_version = "hoshi-setup/1"

    def send_head(self):
        path = self.translate_path(self.path)
        if forbidden(path) or forbidden(self.directory):
            self.send_error(HTTPStatus.FORBIDDEN, "refusing path")
            return None
        if os.path.isdir(path):
            return super().send_head()
        if path.endswith("/"):
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return None
        try:
            handle = open(path, "rb")
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return None
        try:
            size = os.fstat(handle.fileno()).st_size
            ctype = self.guess_type(path)
            span = self._range_span(size)
            if span == "unsatisfiable":
                handle.close()
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            if span is None:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(size))
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                return handle
            start, end = span
            handle.seek(start)
            length = end - start + 1
            self.send_response(HTTPStatus.PARTIAL_CONTENT)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(length))
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            return _LimitedReader(handle, length)
        except Exception:
            handle.close()
            raise

    def _range_span(self, size: int):
        header = self.headers.get("Range")
        if not header or "," in header:
            return None
        match = RANGE_RE.fullmatch(header.strip())
        if match is None:
            return None
        start_text, end_text = match.group(1), match.group(2)
        if start_text == "" and end_text == "":
            return None
        if size == 0:
            return "unsatisfiable"
        if start_text == "":
            suffix = int(end_text)
            if suffix <= 0:
                return "unsatisfiable"
            if suffix >= size:
                return 0, size - 1
            return size - suffix, size - 1
        start = int(start_text)
        end = int(end_text) if end_text else size - 1
        if start >= size or start > end:
            return "unsatisfiable"
        if end >= size:
            end = size - 1
        return start, end


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path.cwd())
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8094)
    args = parser.parse_args()
    directory = os.fspath(args.directory)
    if forbidden(directory):
        raise SystemExit(f"refusing to read {directory}")
    if not os.path.isdir(directory):
        raise SystemExit(f"not a directory: {directory}")
    handler = partial(RangeRequestHandler, directory=directory)
    server = ThreadingHTTPServer((args.bind, args.port), handler)
    print(f"serving {directory} on http://{args.bind}:{args.port}/", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
