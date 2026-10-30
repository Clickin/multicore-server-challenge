#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
JAVA=java
if [[ -x "${JAVA_HOME:-/opt/jdk}/bin/java" ]]; then JAVA="${JAVA_HOME:-/opt/jdk}/bin/java"; fi
exec "$JAVA" -XX:+UseParallelGC -Xms2g -Xmx2g -Xmn768m -XX:+AlwaysPreTouch -Xlog:gc*:file=/tmp/conduit-gc.log:time,uptime:filecount=2,filesize=20m --enable-native-access=ALL-UNNAMED ${JAVA_OPTS:-} -cp 'build/server.jar:lib/jakarta.servlet-api-6.1.0.jar' challenge.Main
