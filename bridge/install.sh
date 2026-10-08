#!/usr/bin/env bash
# Install hoshi-bridge for the current user: the Steam Deck ("deck" user) or a
# Linux PC running mpv. Everything goes into $HOME, so SteamOS's read-only
# root and OS updates are not a problem and no sudo is needed.
#
#   ./install.sh              install or upgrade, enable and (re)start the service
#   ./install.sh --pc         same, with the PC unit and without LunaHook (auto on non-SteamOS)
#   ./install.sh --deck       force the Steam Deck flavour
#   ./install.sh --uninstall  stop and remove the service and binary (keeps config + log)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin_dir="${HOME}/.local/bin"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
unit="hoshi-bridge.service"
legacy_unit="vn-extractor.service"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }

flavour=""
case "${1:-}" in
    --pc) flavour="pc" ;;
    --deck) flavour="deck" ;;
    --uninstall)
        systemctl --user disable --now "$unit" 2>/dev/null || true
        rm -f "$unit_dir/$unit" "$bin_dir/hoshi-bridge"
        systemctl --user daemon-reload || true
        say "Removed. Config (~/.config/hoshi-bridge) and data were kept."
        exit 0
        ;;
    "") ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
esac
if [[ -z "$flavour" ]]; then
    if grep -qs '^ID=steamos' /etc/os-release; then flavour="deck"; else flavour="pc"; fi
fi

src=""
for cand in "$here/hoshi-bridge" "$here/dist/hoshi-bridge"; do
    if [[ -f "$cand" ]]; then src="$cand"; break; fi
done
if [[ -z "$src" ]]; then
    echo "hoshi-bridge binary not found next to install.sh (run 'make release' first)" >&2
    exit 1
fi
unit_file="hoshi-bridge.service"
[[ "$flavour" == "pc" ]] && unit_file="hoshi-bridge-pc.service"
unit_src="$here/deploy/$unit_file"
if [[ ! -f "$unit_src" ]]; then
    echo "$unit_file not found (expected deploy/$unit_file)" >&2
    exit 1
fi

# The bridge was called steamdeck-vn-extractor before; stop the old unit so
# both don't fight over port 7277. Its config is migrated on first start.
if [[ -f "$unit_dir/$legacy_unit" ]]; then
    say "Removing the old $legacy_unit"
    systemctl --user disable --now "$legacy_unit" 2>/dev/null || true
    rm -f "$unit_dir/$legacy_unit" "$bin_dir/vn-extractor"
fi

say "Installing binary to $bin_dir/hoshi-bridge ($flavour)"
mkdir -p "$bin_dir"
install -m 0755 "$src" "$bin_dir/hoshi-bridge.new"
mv -f "$bin_dir/hoshi-bridge.new" "$bin_dir/hoshi-bridge"
"$bin_dir/hoshi-bridge" -version

say "Config"
"$bin_dir/hoshi-bridge" -init

if [[ "$flavour" == "deck" ]]; then
    data_dir="$("$bin_dir/hoshi-bridge" -print-data-dir)/lunahook"
    mkdir -p "$data_dir"
    if [[ -f "$here/lunahook/vnhook.exe" ]]; then
        say "Installing hook helper to $data_dir"
        install -m 0755 "$here/lunahook/vnhook.exe" "$data_dir/vnhook.exe"
        if [[ -f "$here/lunahook/vnhook32.exe" ]]; then
            install -m 0755 "$here/lunahook/vnhook32.exe" "$data_dir/vnhook32.exe"
        fi
    else
        echo "vnhook.exe is not in this package; automatic hooking needs a release built with 'make release'" >&2
    fi
    say "LunaHook DLLs (downloaded once, checked against pinned hashes)"
    if ! "$bin_dir/hoshi-bridge" -setup; then
        echo "LunaHook download failed. The service will still start and retry when a game launches." >&2
        echo "Re-run: hoshi-bridge -setup" >&2
    fi
else
    for tool in ffmpeg mpv; do
        command -v "$tool" >/dev/null 2>&1 || echo "warning: $tool is not on PATH; sentence audio and clips need it" >&2
    done
fi

say "Installing systemd user unit to $unit_dir/$unit"
mkdir -p "$unit_dir"
install -m 0644 "$unit_src" "$unit_dir/$unit"
systemctl --user daemon-reload
systemctl --user enable "$unit" >/dev/null
systemctl --user restart "$unit"
sleep 1
systemctl --user --no-pager --lines=0 status "$unit" || true

linger="$(loginctl show-user "$USER" --property=Linger --value 2>/dev/null || echo unknown)"
if [[ "$flavour" == "deck" && "$linger" != "yes" ]]; then
    cat <<MSG

Note: lingering is off for $USER. The service runs while you are logged in
(SteamOS logs "deck" in automatically in both Game Mode and Desktop Mode),
but it may be stopped and restarted when you switch modes. To keep it running
across mode switches and right from boot, enable lingering once:

    loginctl enable-linger "$USER"
    # if that asks for authentication: sudo loginctl enable-linger "$USER"
    # (sudo on SteamOS needs a password; set one with 'passwd' in Desktop Mode)
MSG
fi
if [[ "$flavour" == "pc" ]]; then
    cat <<MSG

mpv: add this line to ~/.config/mpv/mpv.conf so the bridge can follow playback:
    input-ipc-server=/tmp/mpvsocket
MSG
fi

port="7277"
addr=""
if command -v tailscale >/dev/null 2>&1; then
    addr="$(tailscale ip -4 2>/dev/null | head -n1 || true)"
fi
cat <<MSG

Done. Check it from this machine:
    curl -s http://127.0.0.1:${port}/v2/status
    journalctl --user -u hoshi-bridge -f
In Hoshi Reader (Settings > Live) add the host: $(uname -n), port ${port}${addr:+   (or $addr)}
Edit ~/.config/hoshi-bridge/config.json, then: systemctl --user restart hoshi-bridge
MSG
