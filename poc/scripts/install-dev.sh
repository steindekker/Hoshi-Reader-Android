#!/usr/bin/env bash
# Symlink the add-on into Anki's addons folder for manual testing.
# Close Anki before running; restart it afterward.
set -euo pipefail
ADDONS="$HOME/.local/share/Anki2/addons21"
SRC="$(cd "$(dirname "$0")/.." && pwd)/ankialign"
mkdir -p "$ADDONS"
ln -sfn "$SRC" "$ADDONS/ankialign"
echo "Linked $SRC -> $ADDONS/ankialign"
