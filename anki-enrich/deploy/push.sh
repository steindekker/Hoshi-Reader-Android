#!/usr/bin/env bash
# Deploy anki-enrich to a remote box. Rerunnable: push code + build artifacts,
# (re)build the bot image, refresh the systemd timer. Run from anywhere; creds
# are read from the environment (direnv exports them in the project dir).
#
#   ./deploy/push.sh            # uses DEPLOY_REMOTE / DEPLOY_DEST from the env
#   DEPLOY_REMOTE=<host> ./deploy/push.sh
set -euo pipefail

REMOTE="${DEPLOY_REMOTE:?set DEPLOY_REMOTE to the box SSH alias, e.g. in .envrc}"
DEST="${DEPLOY_DEST:-/opt/anki-enrich}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

say() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# --- Preflight: creds present locally (direnv) -----------------------------
: "${ANKIWEB_USERNAME:?not set — run from the project dir so direnv exports creds}"
: "${ANKIWEB_PASSWORD:?not set — run from the project dir so direnv exports creds}"
: "${TELEGRAM_BOT_TOKEN:?not set — run from the project dir so direnv exports creds}"
: "${TELEGRAM_CHAT_ID:?not set — run from the project dir so direnv exports creds}"

# --- Preflight: required build artifacts exist -----------------------------
for f in var/defs.sqlite var/examples.sqlite var/audio.sqlite; do
  [[ -f "$f" ]] || { echo "missing $f — build it locally first"; exit 1; }
done

# --- Preflight: Docker present on the box ----------------------------------
say "Checking Docker on $REMOTE"
ssh "$REMOTE" 'command -v docker >/dev/null && docker compose version >/dev/null' \
  || { echo "Docker Engine + compose plugin not found on $REMOTE — install it first"; exit 1; }

ssh "$REMOTE" "mkdir -p '$DEST/data'"

# --- Push application code (mirror repo; never touch the box's data/) -------
say "Syncing code to $REMOTE:$DEST"
rsync -az --delete \
  --exclude='/data/' --exclude='var/' --exclude='dicts/' \
  --exclude='.git/' --exclude='.venv/' \
  --exclude='__pycache__/' --exclude='*.pyc' \
  --exclude='.pytest_cache/' --exclude='.ruff_cache/' \
  --exclude='.idea/' --exclude='.claude/' --exclude='.env' --exclude='.envrc' \
  "$REPO_ROOT/" "$REMOTE:$DEST/"

# --- Push build artifacts (always; rsync delta makes reruns cheap) ----------
# audio.sqlite is the ~6.2 GB android.db term-audio store: the FIRST push is a
# large one-time transfer; rsync delta keeps later reruns near-free.
say "Syncing sqlite build artifacts"
artifacts=(var/defs.sqlite var/examples.sqlite var/audio.sqlite)
[[ -f var/dict_styles.css ]] && artifacts+=(var/dict_styles.css)
# freq.sqlite is optional (the mining preview/picker degrade gracefully without
# it); push it when built so the box gets word frequencies + the Frequency field.
[[ -f var/freq.sqlite ]] && artifacts+=(var/freq.sqlite)
rsync -az "${artifacts[@]}" "$REMOTE:$DEST/data/"

# --- Seed the collection once; never clobber the box's live copy ------------
if ssh "$REMOTE" "test -f '$DEST/data/collection.anki2'"; then
  say "Box already has a collection — leaving it (it syncs from AnkiWeb)"
else
  say "Seeding collection + media (first deploy)"
  seed=(var/collection.anki2)
  [[ -f var/collection.media.db2 ]] && seed+=(var/collection.media.db2)
  rsync -az "${seed[@]}" "$REMOTE:$DEST/data/"
  [[ -d var/collection.media ]] && \
    rsync -az var/collection.media/ "$REMOTE:$DEST/data/collection.media/"
fi

# --- Write .env on the box from the local environment (chmod 600) -----------
say "Writing $DEST/.env"
ssh "$REMOTE" "umask 077; cat > '$DEST/.env'" <<EOF
ANKIWEB_USERNAME=$ANKIWEB_USERNAME
ANKIWEB_PASSWORD=$ANKIWEB_PASSWORD
TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN
TELEGRAM_CHAT_ID=$TELEGRAM_CHAT_ID
TZ=Europe/Amsterdam
EOF

# --- Build + (re)start the bot ---------------------------------------------
say "Building image and (re)starting the bot"
ssh "$REMOTE" "cd '$DEST' && docker compose up -d --build"

# --- Rebuild the Hoshi enrichment pack from the freshly pushed indexes -------
# Writes data/pack/{hoshi-pack.sqlite,jrp-accent.sqlite,sudachi-system.dic,pack.json};
# the phone downloads them over Tailscale (see deploy/README.md). Unchanged content
# keeps the same version, so the app only re-downloads a file that actually changed.
say "Building the Hoshi enrichment pack"
ssh "$REMOTE" "cd '$DEST' && docker compose run --rm bot anki-enrich pack"

# --- Install / refresh the nightly timer -----------------------------------
# /etc/systemd/system is root-owned, so this block needs sudo even though the
# rest of the deploy runs as the unprivileged deploy user.
say "Installing systemd units + enabling the nightly timer"
ssh "$REMOTE" "
  sudo cp '$DEST'/deploy/anki-enrich.service '$DEST'/deploy/anki-enrich.timer /etc/systemd/system/ &&
  sudo systemctl daemon-reload &&
  sudo systemctl enable --now anki-enrich.timer &&
  systemctl list-timers anki-enrich.timer --no-pager
"

say "Done. Bot is up on $REMOTE; nightly run fires 04:00 Europe/Amsterdam."
echo "  logs:        ssh $REMOTE 'cd $DEST && docker compose logs -f bot'"
echo "  nightly log: ssh $REMOTE 'journalctl -u anki-enrich.service'"
