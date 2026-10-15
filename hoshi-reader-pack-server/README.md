# hoshi-reader-pack-server

Serves the Hoshi setup directory over HTTP. A download is `sendfile` from disk, so a multi-gigabyte audio file does not enter the process. `Range: bytes=N-` resumes an interrupted download.

```bash
cargo build --release
./target/release/hoshi-reader-pack-server --root ~/hoshi-setup --bind 127.0.0.1 --port 8094
```

The box currently publishes that same directory with `tailscale serve` on port 8094. That adds no process of its own. Use this binary when the file job should be a separate process, and point `tailscale serve` at `http://127.0.0.1:8094`.

It will not serve `/opt/anki-enrich` or `/data/apps/anki-enrich`. It does not build a pack, sync AnkiWeb, or run the Telegram bot.
