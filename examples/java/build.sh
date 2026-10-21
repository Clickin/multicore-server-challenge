#!/usr/bin/env bash
# Build the executable fat jar target/feed.jar with the checked-in Maven wrapper. Idempotent.
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
  if JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then export JAVA_HOME="$JH"; fi
fi
./mvnw -B -q -DskipTests package
ls -l target/feed.jar
