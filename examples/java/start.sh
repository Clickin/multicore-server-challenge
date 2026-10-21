#!/usr/bin/env bash
# Foreground server (systemd ExecStart). Config from env: DATABASE_URL, JWT_SECRET, HOST, PORT, optional EXTRA_PORT, DB_POOL.
#   -XX:+UseG1GC            G1 explicitly: with 1 usable CPU the JVM's ergonomics would pick SerialGC.
#   -XX:MaxRAMPercentage=70 heap up to 70% of RAM (default 25%): ~1M connections' Tomcat state lives on heap.
#   MAX_RAM_PCT overrides the 70 (heap-size experiments only).
#   -XX:InitialRAMPercentage=10  start with a reasonable heap instead of growing from 1/64 of RAM.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# Linux: run on the JDK 21 install.sh put in (a JAVA_HOME pointing at another JDK, as on CI runners, would win otherwise)
if [ -d /usr/lib/jvm/java-21-openjdk-amd64 ] && [ "$(uname -s)" = Linux ]; then JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA" -server -XX:+UseG1GC -XX:MaxRAMPercentage=${MAX_RAM_PCT:-70} -XX:InitialRAMPercentage=10 \
  -jar "$DIR/target/feed.jar"
