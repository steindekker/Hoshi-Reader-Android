# Deploying anki-enrich to a Hetzner box

The box runs the Telegram `bot` (on-demand `/run`) and a nightly systemd timer.
It needs only the code + ~1.4 GB of prebuilt sqlite — never the 4.2 GB `dicts/`
corpus. The collection bootstraps itself from AnkiWeb on first run.

## Prerequisites
- **On the box:** Docker Engine + the compose plugin (`docker compose version`),
  and systemd ≥ 252 (the timer pins `04:00 Europe/Amsterdam` via a timezone
  suffix in `OnCalendar`; Debian 12 / Ubuntu 24.04 and newer are fine).
- **Locally:** `DEPLOY_REMOTE` set to the box's SSH alias (kept in `.envrc`),
  the four creds in your environment (direnv loads them in the project dir),
  and the prebuilt `var/defs.sqlite` + `var/examples.sqlite`.

## Deploy

```bash
./deploy/push.sh                                  # → $DEPLOY_REMOTE:/opt/anki-enrich
DEPLOY_REMOTE=<host> DEPLOY_DEST=/srv/ae ./deploy/push.sh   # override host/path
```

The script is **rerunnable** — run it again any time the code changes. Each run:
1. Checks Docker is present on the box.
2. rsyncs the code (mirrored with `--delete`, but the box's `data/` is excluded
   so the collection is never touched).
3. Pushes `defs.sqlite` / `examples.sqlite` / `audio.sqlite` (rsync delta → near-free
   if unchanged; `audio.sqlite` is ~2 GB but rarely changes, so reruns stay cheap).
4. **Seeds** `collection.anki2` + media only on the *first* deploy; on later runs
   it leaves the box's live, AnkiWeb-synced collection alone.
5. Writes `/opt/anki-enrich/.env` (mode 600) from your local creds — secrets are
   never committed to the repo.
6. `docker compose up -d --build` (rebuilds + restarts the bot).
7. Installs/refreshes the systemd units and enables the nightly timer.

First deploy without a seeded collection still works: the first enrichment run
opens a fresh `data/collection.anki2` and full-downloads from AnkiWeb (~900 MB).
Term audio ships as the prebuilt `audio.sqlite` artifact (built locally from the
local-audio-yomichan add-on, like `defs.sqlite`/`examples.sqlite` are built from
`dicts/`); the add-on is only needed on the build machine. Absent the artifact, the
audio step self-skips rather than failing.

## Refresh dictionaries
Rebuild the dicts locally, then just rerun the deploy — it re-pushes the sqlite
and restarts the bot:
```bash
./deploy/push.sh
```

## Operating
- **Trigger a run now:** Telegram `/run` → Apply.
- **Run on the box directly:** `docker compose run --rm bot anki-enrich --apply --notify`
- **Bot logs:** `docker compose logs -f bot`
- **Nightly run logs:** `journalctl -u anki-enrich.service`
- A manual `/run` and the nightly run can't collide: whichever is second hits the
  `run.lock` and bows out (`Busy`).
