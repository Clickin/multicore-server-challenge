#!/usr/bin/env bash
# Install Node.js 22 LTS (official linux-x64 tarball) into /opt/node22. Idempotent; run as root.
# The box's apt node (v18) is too old for Fastify 5, so we don't use it.
# pg (node-postgres) is pure JavaScript: no native build toolchain needed.
set -euo pipefail
NODE_VERSION=v22.23.3
DEST=/opt/node22
if [ -x "$DEST/bin/node" ] && [ "$("$DEST/bin/node" -v)" = "$NODE_VERSION" ]; then
  echo "node $NODE_VERSION already in $DEST"
else
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
  base="https://nodejs.org/dist/$NODE_VERSION"
  file="node-$NODE_VERSION-linux-x64.tar.xz"
  curl -fsSL -o "$tmp/$file" "$base/$file"
  curl -fsSL -o "$tmp/SHASUMS256.txt" "$base/SHASUMS256.txt"
  (cd "$tmp" && grep " $file\$" SHASUMS256.txt | sha256sum -c -)
  rm -rf "$DEST.new"; mkdir -p "$DEST.new"
  tar -xJf "$tmp/$file" -C "$DEST.new" --strip-components=1
  rm -rf "$DEST"; mv "$DEST.new" "$DEST"
fi
PATH="$DEST/bin:$PATH"; node -v; npm -v
