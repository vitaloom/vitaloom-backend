#!/bin/sh
set -eu

log=/var/log/vitaloom/rate-limit.log
max_bytes=${1:-10485760}

if [ ! -f "$log" ] || [ "$(stat -c %s "$log")" -lt "$max_bytes" ]; then
    exit 0
fi

if [ -f "$log.1" ]; then
    mv -f "$log.1" "$log.2"
fi
mv "$log" "$log.1"
if ! nginx -s reopen; then
    mv "$log.1" "$log"
    exit 1
fi
