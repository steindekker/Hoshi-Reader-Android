# hoshi-reader-pack-server

Serves the Hoshi setup directory over HTTP. A download is `sendfile` from disk, so a multi-gigabyte audio file does not enter the process. `Range: bytes=N-` resumes an interrupted download.

```bash
cargo build --release
./target/release/hoshi-reader-pack-server --root ~/hoshi-setup --bind 127.0.0.1 --port 8094
```

With `--token-file PATH`, every request must send that file's token in `X-Hoshi-Token`; anything else gets `401`. Every response carries `Cache-Control: private, no-store`, so a proxy in front (Cloudflare) never caches a file and serves it without the token. The app sends the token it was built with (`HOSHI_SETUP_TOKEN`, or `~/.config/hoshi-reader/setup-token`).

## Public deployment on box

`setup.example.com` is a dedicated Cloudflare tunnel (`hoshi-setup`) to this server on `127.0.0.1:8095`. Both run as user services (`deploy/`, lingering is on for `stein`):

```bash
cargo build --release
scp target/release/hoshi-reader-pack-server <host>:.local/bin/
scp ~/.config/hoshi-reader/setup-token <host>:.config/hoshi-setup/token   # mode 600
scp deploy/*.service <host>:.config/systemd/user/
ssh <host> 'systemctl --user daemon-reload && systemctl --user enable --now hoshi-setup-server hoshi-setup-tunnel'
```

The tunnel's `~/.cloudflared/hoshi-setup.yml` routes `setup.example.com` to `http://127.0.0.1:8095`. `tailscale serve` still publishes the same directory on `<host>:8094` for builds older than the public catalog.

It will not serve `/opt/anki-enrich` or `/data/apps/anki-enrich`. It does not build a pack, sync AnkiWeb, or run the Telegram bot.
