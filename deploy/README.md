# Deploying anki-enrich to a Hetzner box

The box runs the Telegram `bot` (on-demand `/run`) and a nightly systemd timer.
It needs only the code + ~1.4 GB of prebuilt sqlite — never the 4.2 GB `dicts/`
corpus. The collection bootstraps itself from AnkiWeb on first run.

## Prerequisites on the box
- Docker Engine + the compose plugin (`docker compose version`).
- A directory `/opt/anki-enrich` (the paths below assume it).

## First deploy
1. **Get the code:**
   ```bash
   sudo git clone <repo-url> /opt/anki-enrich
   cd /opt/anki-enrich
   ```
2. **Ship the prebuilt sqlite** (run on your local machine, where they were built):
   ```bash
   rsync -avP var/defs.sqlite var/examples.sqlite \
     <box>:/opt/anki-enrich/data/
   ```
   These land in the bind-mounted volume (`./data` → `/app/var`). Because they
   exist, the build step is skipped on first run.
3. **Create `.env`** from the template and fill in all four values:
   ```bash
   cp .env.example .env && $EDITOR .env
   ```
4. **Start the bot:**
   ```bash
   docker compose up -d --build
   ```
   First enrichment run (manual `/run` or the nightly timer) opens a fresh
   `data/collection.anki2` and full-downloads the collection + media from
   AnkiWeb (~900 MB). The audio step self-skips (no desktop add-on present).
5. **Install the nightly timer:**
   ```bash
   sudo cp deploy/anki-enrich.service deploy/anki-enrich.timer /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now anki-enrich.timer
   systemctl list-timers anki-enrich.timer   # confirm next fire
   ```

## Redeploy (new code)
```bash
cd /opt/anki-enrich
git pull
docker compose up -d --build
```
The timer keeps using the freshly built image automatically (it runs `compose run`).

## Refresh dictionaries
Only when you rebuild the dicts locally — re-push the sqlite and restart:
```bash
rsync -avP var/defs.sqlite var/examples.sqlite <box>:/opt/anki-enrich/data/
ssh <box> 'cd /opt/anki-enrich && docker compose restart bot'
```

## Operating
- **Trigger a run now:** Telegram `/run` → Apply.
- **Run on the box directly:** `docker compose run --rm bot anki-enrich --apply --notify`
- **Bot logs:** `docker compose logs -f bot`
- **Nightly run logs:** `journalctl -u anki-enrich.service`
- A manual `/run` and the nightly run can't collide: whichever is second hits the
  `run.lock` and bows out (`Busy`).
