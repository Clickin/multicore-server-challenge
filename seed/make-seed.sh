#!/usr/bin/env bash
# Builds the seed database `feed_seed` in Postgres (50k users, 500k posts, ~2M likes) plus seed/tokens.json.
#
#   bash seed/make-seed.sh
#
# Needs: node >= 18, psql, and a Postgres >= 16 you can create databases on. Connection settings come from the
# usual libpq env vars (PGHOST, PGPORT, PGUSER, PGPASSWORD); by default the local server as your OS user.
# Takes about a minute. The output is deterministic: the check at the end must print OK.
# Never serve feed_seed directly: every test or benchmark run gets a fresh copy
# (CREATE DATABASE feed TEMPLATE feed_seed), which test/run.sh does for you.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TSV="$HERE/tsv"; DB=feed_seed
EXPECTED=738d11ab3164c13177e347f75038d1e80b43c64e754544a42d1c32bd8d7bcf1d  # sha256 of every row, ordered
q() { psql -qX -v ON_ERROR_STOP=1 "$@"; }

node "$HERE/generate.mjs" "$TSV"
mv "$TSV/tokens.json" "$HERE/tokens.json"

q -d postgres -c "DROP DATABASE IF EXISTS $DB WITH (FORCE)" -c "CREATE DATABASE $DB"
sed '/^-- after the bulk load --$/,$d' "$HERE/../schema.sql" | q -d $DB
q -d $DB -c "\\copy users (id, username, created_at) FROM '$TSV/users.tsv'" \
         -c "\\copy posts (id, user_id, body, created_at) FROM '$TSV/posts.tsv'" \
         -c "\\copy likes (user_id, post_id, created_at) FROM '$TSV/likes.tsv'"
sed -n '/^-- after the bulk load --$/,$p' "$HERE/../schema.sql" | q -d $DB -c "SET maintenance_work_mem = '256MB'" -f - >/dev/null
q -d $DB -c "VACUUM ANALYZE"
rm -rf "$TSV"

# Same content check as the SQLite seed of the $12 challenge: every row, ordered, '|'-separated, timestamps as stored there.
ts="to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"')"
sha() { if command -v sha256sum >/dev/null; then sha256sum; else shasum -a 256; fi; }
sum="$(psql -qXAt -F'|' -d $DB \
  -c "SELECT id, username, $ts FROM users ORDER BY id" \
  -c "SELECT id, user_id, body, $ts FROM posts ORDER BY id" \
  -c "SELECT user_id, post_id, $ts FROM likes ORDER BY post_id, user_id" | sha | cut -d' ' -f1)"
size="$(psql -qXAt -d postgres -c "SELECT pg_size_pretty(pg_database_size('$DB'))")"
if [ "$sum" = "$EXPECTED" ]; then echo "OK: database $DB ($size), content sha256 matches"
else echo "MISMATCH: content sha256 $sum, expected $EXPECTED"; exit 1; fi
