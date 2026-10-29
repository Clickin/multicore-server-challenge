#!/usr/bin/env bash
set -euo pipefail
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
cat > "$dir/nproc" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$TEST_CPUS"
MOCK
cat > "$dir/uname" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$TEST_OS"
MOCK
cat > "$dir/java" <<'MOCK'
#!/usr/bin/env bash
printf '%s\n' "$@"
MOCK
chmod +x "$dir/nproc" "$dir/uname" "$dir/java"

check() {
  local cpus=$1 os=$2 memory=$3 loops=$4 gzip=$5 pool=$6 pipeline=$7 heap=$8
  shift 8
  printf 'MemTotal: %s kB\n' "$memory" > "$dir/meminfo"
  env -u MU_EVENT_LOOPS -u MU_GZIP_LEVEL -u MU_DB_POOL -u MU_DB_PIPELINE -u MU_HEAP \
    PATH="$dir:$PATH" TEST_CPUS="$cpus" TEST_OS="$os" MU_MEMINFO="$dir/meminfo" "$@" \
    bash "$(dirname "$0")/../../start.sh" > "$dir/args" 2> "$dir/config"
  printf 'event_loops=%s gzip_level=%s db_pool_requested=%s db_pipeline=%s heap=%s\n' \
    "$loops" "$gzip" "$pool" "$pipeline" "$heap" > "$dir/expected-config"
  cat > "$dir/expected-args" <<EOF
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
-XX:+UseZGC
-Xms$heap
-Xmx$heap
-XX:+AlwaysPreTouch
-XX:+UseCompactObjectHeaders
-Dvertx.disableHttpHeadersValidation=true
-Dio.netty.buffer.checkBounds=false
-Dio.netty.buffer.checkAccessible=false
-Dmu.event-loops=$loops
-Dmu.gzip-level=$gzip
-Dmu.db-pool=$pool
-Dmu.db-pipeline=$pipeline
-jar
target/feed.jar
EOF
  diff -u "$dir/expected-config" "$dir/config"
  diff -u "$dir/expected-args" "$dir/args"
}
check 1 Linux 1048576 1 0 64 256 1g
check 4 Linux 7340032 4 0 64 256 3g
check 7 Linux 16777216 7 0 64 256 8g
check 8 Linux 16777216 8 1 64 256 8g
check 16 Linux 33554432 16 1 64 256 16g
check 4 Linux 1048576 3 9 128 32 128m MU_EVENT_LOOPS=3 MU_GZIP_LEVEL=9 MU_DB_POOL=128 MU_DB_PIPELINE=32 MU_HEAP=128m
check 8 Linux 4194304 8 1 64 256 2g MU_EVENT_LOOPS= MU_GZIP_LEVEL= MU_DB_POOL= MU_DB_PIPELINE= MU_HEAP=
check 4 Darwin 33554432 4 0 64 256 2g
printf 'Startup configuration checks passed\n'
