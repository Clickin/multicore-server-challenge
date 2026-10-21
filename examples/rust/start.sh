#!/usr/bin/env bash
# Run the server in the foreground (systemd ExecStart). Config from the environment:
# DATABASE_URL, JWT_SECRET, HOST, PORT, optional EXTRA_PORT and DB_POOL. Worker threads and pool size follow the CPU affinity mask.
set -euo pipefail
# Raise the soft open-files limit to the hard limit (a no-op under systemd LimitNOFILE=4194304, which sets
# both). From an interactive shell the soft limit is often 1024: tokio does not raise it itself (unlike
# Go's runtime or the JVM), and axum's accept loop then silently sleeps 1 s on EMFILE while new
# connections pile up in the kernel accept queue.
ulimit -n "$(ulimit -Hn)"
exec "$(cd "$(dirname "$0")" && pwd)/target/release/feed-api"
