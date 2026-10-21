#!/usr/bin/env bash
# Install the exact dependency tree from package-lock.json (production only). Idempotent.
set -euo pipefail
cd "$(dirname "$0")"
export PATH=/opt/node22/bin:$PATH
npm ci --omit=dev --no-audit --no-fund
node -e "require('pg'); require('fastify'); console.log('deps ok, node', process.version)"
