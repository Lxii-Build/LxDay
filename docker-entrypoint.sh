#!/bin/sh
set -eu

# Named volumes created by older root-running images can remain root-owned after
# the container switches to uid 10001. Repair each volume once, then keep the
# application process unprivileged for the rest of its lifetime.
for dir in /app/data /app/uploads /app/uploads-private; do
    mkdir -p "$dir"
    marker="$dir/.lxday-permissions-v1"
    if [ ! -e "$marker" ]; then
        chown -R app:app "$dir"
        touch "$marker"
        chown app:app "$marker"
    fi
done

exec su-exec app "$@"
