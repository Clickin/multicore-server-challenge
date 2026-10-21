#!/usr/bin/env bash
# Foreground (systemd ExecStart). Config from env: DATABASE_URL, JWT_SECRET, HOST, PORT, EXTRA_PORT (optional), DB_POOL (optional).
# The cluster primary forks one worker per CPU in this process's affinity mask.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
export NODE_ENV=production
# Heap room: V8's default old-space cap is ~4 GB per process regardless of RAM. At 1M connections
# on few cores one worker can hold 250k-1M sockets, so give the workers 70% of RAM split between them
# (nproc respects CPU affinity, same count the cluster primary uses).
if [ -z "${NODE_OPTIONS:-}" ]; then
  mem_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
  workers=$(nproc)
  heap=$(( mem_mb * 70 / 100 / workers ))
  [ "$heap" -lt 4096 ] && heap=4096
  export NODE_OPTIONS="--max-old-space-size=$heap"
fi
exec /opt/node22/bin/node "$DIR/server.js"
