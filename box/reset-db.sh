#!/usr/bin/env bash
# On the database box, before every benchmark run: a fresh "feed" database from the feed_seed template, then
# Postgres restarted with cold caches. Your server never sees state from a previous run.
set -euo pipefail
systemctl restart postgresql@18-main
sudo -u postgres psql -qX -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS feed WITH (FORCE)" -c "CREATE DATABASE feed OWNER feed TEMPLATE feed_seed"
systemctl stop postgresql@18-main; sync; echo 3 > /proc/sys/vm/drop_caches; systemctl start postgresql@18-main
echo "reset: feed from feed_seed"
