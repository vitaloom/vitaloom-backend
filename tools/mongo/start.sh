#!/bin/bash
set -euo pipefail

keyfile=/data/configdb/vitaloom-keyfile
if [ ! -s "$keyfile" ]; then
    umask 077
    dd if=/dev/urandom bs=756 count=1 status=none | base64 | tr -d '\n' > "${keyfile}.tmp"
    mv "${keyfile}.tmp" "$keyfile"
fi

chown mongodb:mongodb "$keyfile"
chmod 400 "$keyfile"

# Keep the official image's first-run initialization and privilege-drop behavior.
exec /usr/local/bin/docker-entrypoint.sh "$@"
