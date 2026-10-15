#!/usr/bin/env python3
"""Recreate the Hoshi setup catalog (setup.json format 1).

Copies the local Yomitan zips, downloads JMdict and KANJIDIC, and calls
ankienrich.sentence_model.build for jrp-accent.sqlite and sudachi-system.dic.
Does not build the enrichment pack. Audio is omitted. Refuses to read any
path containing /opt/anki-enrich or /data/apps/anki-enrich.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VENV_PYTHON = REPO / "anki-enrich" / ".venv" / "bin" / "python"
DICT_DIR = Path("~/dicts/yomitan")
FORBIDDEN = ("/opt/anki-enrich", "/data/apps/anki-enrich")

JMDICT_URL = (
    "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/"
    "JMdict_english_with_examples.zip"
)
KANJIDIC_URL = (
    "https://github.com/yomidevs/jmdict-yomitan/releases/latest/download/"
    "KANJIDIC_english.zip"
)

# Install order. Titles are the Yomitan index.json checks, not display names.
DICTIONARIES = (
    {
        "id": "sankoku8",
        "file": "sankoku8.zip",
        "src": DICT_DIR / "[JA-JA] 三省堂国語辞典\u3000第八版.zip",
        "title": "三省堂国語辞典\u3000第八版",
    },
    {
        "id": "jitsuyo",
        "file": "jitsuyo.zip",
        "src": DICT_DIR / "[JA-JA] 実用日本語表現辞典.zip",
        "title": "実用日本語表現辞典",
    },
    {
        "id": "jmdict",
        "file": "jmdict-english.zip",
        "url": JMDICT_URL,
        "title_contains": "JMdict",
    },
    {
        "id": "nhk-pitch",
        "file": "nhk-pitch.zip",
        "src": DICT_DIR / "[Pitch] NHK2016.zip",
        "title": "NHK",
        "revision": "pitch_1.0.1.1",
    },
    {
        "id": "jpdb-freq",
        "file": "jpdb-v2.2-freq-kana.zip",
        "src": DICT_DIR / "[JA Freq] JPDB_v2.2_Frequency_Kana_2024-10-13.zip",
        "title": "JPDBv2\u32d5",
        "frequency_mode": "rank-based",
    },
    {
        "id": "kanjidic",
        "file": "kanjidic-english.zip",
        "url": KANJIDIC_URL,
        "title_contains": "KANJIDIC",
    },
)

SENTENCE_FIELDS = ("file", "version", "size", "sha256")
SENTENCE_BUILD = r"""
import json, os, sys
from pathlib import Path
from ankienrich.paths import ACCENT_INDEX
from ankienrich.sentence_model import build, dictionary_source

forbidden = ("/opt/anki-enrich", "/data/apps/anki-enrich")

def refuse(label, path):
    text = os.fspath(path)
    for snippet in forbidden:
        if snippet in text.replace("\\", "/"):
            raise SystemExit(f"refusing to read {label}: {text}")

refuse("accent index", ACCENT_INDEX)
source = dictionary_source()
if source is None:
    raise SystemExit("sentence_model.build has no Sudachi dictionary")
refuse("sudachi dictionary", source[0])
stage = Path(sys.argv[1])
refuse("sentence stage", stage)
result = build(stage)
if not result:
    raise SystemExit("sentence_model.build returned nothing")
Path(sys.argv[2]).write_text(json.dumps(result), encoding="utf-8")
"""


def refuse(path: Path) -> None:
    """Reject forbidden paths before any stat or read."""
    raw = os.fspath(path)
    absolute = os.path.abspath(raw)
    for text in (raw, absolute):
        normalized = text.replace("\\", "/")
        for snippet in FORBIDDEN:
            if snippet in normalized:
                raise SystemExit(f"refusing to read {path}: path contains {snippet}")


def sha256(path: Path) -> str:
    refuse(path)
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_index(path: Path) -> dict:
    refuse(path)
    with zipfile.ZipFile(path) as archive:
        try:
            raw = archive.read("index.json")
        except KeyError:
            raise SystemExit(f"{path} has no root index.json") from None
    try:
        data = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SystemExit(f"{path} index.json is not JSON: {exc}") from None
    if data.get("format") != 3:
        raise SystemExit(f"{path} index.json format is {data.get('format')!r}, expected 3")
    return data


def check_index(spec: dict, index: dict, path: Path) -> None:
    title = index.get("title") or ""
    if "title" in spec and title != spec["title"]:
        raise SystemExit(f"{path} title is {title!r}, expected {spec['title']!r}")
    if "title_contains" in spec and spec["title_contains"] not in title:
        raise SystemExit(f"{path} title {title!r} does not mention {spec['title_contains']}")
    if "revision" in spec and index.get("revision") != spec["revision"]:
        raise SystemExit(
            f"{path} revision is {index.get('revision')!r}, expected {spec['revision']!r}"
        )
    if "frequency_mode" in spec and index.get("frequencyMode") != spec["frequency_mode"]:
        raise SystemExit(
            f"{path} frequencyMode is {index.get('frequencyMode')!r}, "
            f"expected {spec['frequency_mode']!r}"
        )
    print(f"verified {spec['file']}: title={title!r} revision={index.get('revision')!r}", flush=True)


def place_file(src: Path, dest: Path) -> None:
    refuse(src)
    refuse(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".partial")
    refuse(partial)
    shutil.copyfile(src, partial)
    os.chmod(partial, 0o644)
    os.replace(partial, dest)


def download(url: str, dest: Path) -> None:
    refuse(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".partial")
    refuse(partial)
    print(f"downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "hoshi-setup"})
    try:
        with urllib.request.urlopen(request, timeout=180) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, length=1 << 20)
    except Exception:
        partial.unlink(missing_ok=True)
        raise
    os.chmod(partial, 0o644)
    os.replace(partial, dest)


def file_record(path: Path) -> dict:
    refuse(path)
    return {"file": path.name, "size": path.stat().st_size, "sha256": sha256(path)}


def build_sentence(stage: Path) -> dict:
    refuse(stage)
    refuse(VENV_PYTHON)
    if not VENV_PYTHON.is_file():
        raise SystemExit(f"missing venv python: {VENV_PYTHON}")
    stage.mkdir(parents=True, exist_ok=True)
    manifest_path = stage / "sentence.json"
    refuse(manifest_path)
    env = os.environ.copy()
    for key, value in list(env.items()):
        if value and any(snippet in value.replace("\\", "/") for snippet in FORBIDDEN):
            env.pop(key, None)
    env.pop("ANKI_ENRICH_VAR", None)
    print("building sentence model", flush=True)
    completed = subprocess.run(
        [str(VENV_PYTHON), "-c", SENTENCE_BUILD, str(stage), str(manifest_path)],
        cwd=REPO / "anki-enrich",
        env=env,
        text=True,
        capture_output=True,
        timeout=900,
        check=False,
    )
    if completed.returncode != 0:
        sys.stderr.write(completed.stdout)
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"sentence_model.build failed ({completed.returncode})")
    refuse(manifest_path)
    result = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest_path.unlink()
    sentence = {"format": 1}
    for key, filename in (("accent", "jrp-accent.sqlite"), ("dictionary", "sudachi-system.dic")):
        entry = result.get(key) or {}
        if entry.get("file") != filename:
            raise SystemExit(f"sentence {key} file is {entry.get('file')!r}, expected {filename}")
        built = stage / filename
        record = file_record(built)
        if record["size"] != entry.get("size") or record["sha256"] != entry.get("sha256"):
            raise SystemExit(f"sentence {key} hash does not match sentence_model.build")
        if not entry.get("version"):
            raise SystemExit(f"sentence {key} version is empty")
        sentence[key] = {
            "file": filename,
            "version": entry["version"],
            "size": record["size"],
            "sha256": record["sha256"],
        }
    return sentence


def publish(out: Path) -> None:
    refuse(out)
    var_dir = (REPO / "anki-enrich" / "var").resolve()
    if out.resolve().is_relative_to(var_dir):
        raise SystemExit(f"refusing to write into {var_dir}")
    out.mkdir(parents=True, exist_ok=True)
    dictionaries = []
    for spec in DICTIONARIES:
        dest = out / spec["file"]
        if "url" in spec:
            download(spec["url"], dest)
        else:
            print(f"copying {spec['src'].name}", flush=True)
            place_file(spec["src"], dest)
        index = read_index(dest)
        check_index(spec, index, dest)
        record = file_record(dest)
        dictionaries.append({"id": spec["id"], **record})

    with tempfile.TemporaryDirectory(prefix="hoshi-sentence-", dir=out) as stage_name:
        stage = Path(stage_name)
        sentence = build_sentence(stage)
        for key in ("accent", "dictionary"):
            filename = sentence[key]["file"]
            place_file(stage / filename, out / filename)
            copied = file_record(out / filename)
            if copied["size"] != sentence[key]["size"] or copied["sha256"] != sentence[key]["sha256"]:
                raise SystemExit(f"copied {filename} does not match the staged sentence file")

    manifest = {"format": 1, "dictionaries": dictionaries, "sentence": sentence}
    if "audio" in manifest:
        raise SystemExit("audio must not be published")
    payload = json.dumps(manifest, indent=2) + "\n"
    setup = out / "setup.json"
    refuse(setup)
    setup.write_text(payload, encoding="utf-8")
    os.chmod(setup, 0o644)
    print(payload, end="", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="Directory to publish into")
    args = parser.parse_args()
    try:
        publish(args.out)
    except subprocess.TimeoutExpired as exc:
        raise SystemExit(f"sentence_model.build timed out: {exc}") from None


if __name__ == "__main__":
    main()
