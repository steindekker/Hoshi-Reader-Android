# hoshi-reader-pack-server

Serves the Hoshi setup directory over HTTP. A download is `sendfile` from disk, so a multi-gigabyte audio file does not enter the process. `Range: bytes=N-` resumes an interrupted download.

```bash
cargo build --release
./target/release/hoshi-reader-pack-server --root ~/hoshi-setup --bind 127.0.0.1 --port 8095 --token-file ~/.config/hoshi-setup/token
```

With `--token-file PATH`, every request must send that file's token in `X-Hoshi-Token`; anything else gets `401`. Every response carries `Cache-Control: private, no-store`, so a proxy in front (Cloudflare) never caches a file and serves it without the token. The app sends the token and catalog URL it was built with (`HOSHI_SETUP_TOKEN` / `HOSHI_SETUP_URL`, or `~/.config/hoshi-reader/setup-token` / `setup-url`); neither is kept in this repository.

## Public deployment

The server sits behind a dedicated Cloudflare tunnel (`hoshi-setup`) that routes your public setup hostname to `127.0.0.1:8095`. Both run as user services from `deploy/` (enable lingering for the user). `$SETUP_HOST` is the machine's SSH alias:

```bash
cargo build --release
scp target/release/hoshi-reader-pack-server "$SETUP_HOST":.local/bin/
scp ~/.config/hoshi-reader/setup-token "$SETUP_HOST":.config/hoshi-setup/token   # mode 600
scp deploy/*.service "$SETUP_HOST":.config/systemd/user/
ssh "$SETUP_HOST" 'systemctl --user daemon-reload && systemctl --user enable --now hoshi-setup-server hoshi-setup-tunnel'
```

The tunnel's `~/.cloudflared/hoshi-setup.yml` routes the setup hostname to `http://127.0.0.1:8095`.

It will not serve `/opt/anki-enrich` or `/data/apps/anki-enrich`. It does not build a pack, sync AnkiWeb, or run the Telegram bot.
