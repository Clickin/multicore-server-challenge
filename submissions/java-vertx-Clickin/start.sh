#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
cpus=$(nproc)
event_loops=${MU_EVENT_LOOPS:-$cpus}
gzip_level=${MU_GZIP_LEVEL:-$(( cpus >= 8 ? 1 : 0 ))}
db_pool=${MU_DB_POOL:-64}
db_pipeline=${MU_DB_PIPELINE:-256}
if [[ -n ${MU_HEAP:-} ]]; then
  heap=$MU_HEAP
elif [[ $(uname -s) == Linux ]]; then
  heap=$(awk '/^MemTotal:/ { size = int($2 / 2097152); print (size < 1 ? 1 : size) "g"; exit }' "${MU_MEMINFO:-/proc/meminfo}")
  [[ -n $heap ]] || { echo 'MemTotal not found' >&2; exit 1; }
else
  heap=2g
fi
printf 'event_loops=%s gzip_level=%s db_pool_requested=%s db_pipeline=%s heap=%s\n' \
  "$event_loops" "$gzip_level" "$db_pool" "$db_pipeline" "$heap" >&2
exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -XX:+UseZGC "-Xms$heap" "-Xmx$heap" -XX:+AlwaysPreTouch -XX:+UseCompactObjectHeaders \
  -Dvertx.disableHttpHeadersValidation=true -Dio.netty.buffer.checkBounds=false -Dio.netty.buffer.checkAccessible=false \
  "-Dmu.event-loops=$event_loops" "-Dmu.gzip-level=$gzip_level" "-Dmu.db-pool=$db_pool" "-Dmu.db-pipeline=$db_pipeline" \
  -jar target/feed.jar
