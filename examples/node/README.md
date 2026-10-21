# std-node-pg: Node.js 22 + Fastify 5 + pg (node-postgres)

This is the Postgres variant of the reviewed `std-node` entry. Only two things changed: the data layer (better-sqlite3 became a `pg` Pool) and a second listening port (`EXTRA_PORT`). Everything else is the same, with the same reasons, as `../std-node/README.md`: cluster model, the 1-core single-process rule, keep-alive, timeouts, backlog, compression, heap and logging. It passes `test/test.sh` with `passed=42 failed=0` on both `PORT` and `EXTRA_PORT`.

## Stack

| Piece | Version |
|---|---|
| Node.js | v22.23.3 LTS, the official linux-x64 tarball installed in `/opt/node22` (npm 10.9.9) |
| Fastify | 5.12.7 (`logger: false`) |
| @fastify/compress | 9.2.2 (gzip) |
| pg (node-postgres) | 8.23.1, pure JS (no `pg-native`) |
| fast-jwt | 6.3.5 (the JWT library that `@fastify/jwt` uses) |
| Postgres | 18, on a separate box, reached through `DATABASE_URL` |

The full tree is pinned in `package-lock.json`. `build.sh` runs `npm ci --omit=dev`.

## Config (env)

`DATABASE_URL` (`postgres://user:pass@host:5432/db`), `JWT_SECRET`, `HOST`, `PORT`, `EXTRA_PORT` (optional second port), `DB_POOL` (optional, sets the pool size per process).

## Process model (unchanged from std-node)

- **node:cluster, one worker per CPU, from `os.availableParallelism()`.** That reads `sched_getaffinity`, so it follows systemd `CPUAffinity`/`AllowedCPUs` and `taskset`. The primary only forks and restarts workers. It runs Node's default `SCHED_RR`, where the primary accepts each connection and hands it to a worker round-robin.
- **On 1 CPU, one plain process with no cluster.** See std-node's README for the measurement behind this.
- Each worker runs one Fastify instance on a single event loop. The handlers are `async` and `await` the pool.

## Database: one `pg.Pool` per worker process

| Setting | Value | Why |
|---|---|---|
| `max` | Cluster: `max(2, floor(128 / workers))` per worker, so the app total is at most 128: 16 workers get 8 each, 8 get 16, 4 get 32, 2 get 64. One core (single process): `min(128, 8 x cores)` = 8. Override with `DB_POOL`. | The benchmark-wide -pg rule (raised from 64 to 128 after the Go review found throughput pool-limited: each connection serves only ~600-1,600 req/s because of the network round trip, and 1M users need ~42k req/s). Node is multi-process, so the budget is split over the workers. The 1-core value is measured, see below. |
| `connectionTimeoutMillis` | 5000 | The acquire timeout. When the pool is exhausted, requests queue in the pool; after 5 s one gets a 500 JSON (`timeout exceeded when trying to connect` is logged) and the worker keeps serving. pg's default is to wait forever. Verified with `DB_POOL=1` and the only connection blocked on a table lock. |
| `idleTimeoutMillis` | 300000 (5 min; pg default 10 s) | pg's pool hands out the most recently used idle client first, so with a pool larger than the momentary need the others sit idle, hit the 10 s default, close, and get reopened on the next burst. Measured at 4 cores (4 x 32 = 128 slots), 10k users, ~1,900 req/s, 60 s: 291 distinct backends for 128 slots with the default (about 160 reconnects a minute, each a SCRAM login), 87 with 300 s and none closed. 5 min matches Npgsql's `ConnectionIdleLifetime` default; pgx keeps idle connections 30 min, sqlx 10 min, HikariCP (fixed pool) never closes them, so this puts Node in line with the other -pg entries. |
| `pool.on('error')` | logs | node-postgres's documented guard. An idle client that errors (network drop, server restart) emits `error` on the pool, and without a listener the worker would crash. |
| Named queries (`{ name, text, values }`) | all 5 statements | node-postgres's documented way to prepare a statement once per connection and reuse it. The SQL is the spec's verbatim text with `$1` placeholders. Each request runs one autocommit statement through `pool.query`. |

- **created_at**: node-postgres returns `TIMESTAMPTZ` as a JS `Date`, which holds milliseconds, so the microseconds are truncated, not rounded: postgres-date 1.0.7 builds it with `Date.UTC(..., 1000 * parseFloat(fraction))` and `Date` drops the fractional ms. Checked for all 1,000,000 microsecond values: the ms digits always equal the first three digits (`.999999` gives `.999Z`). The app calls `Date.prototype.toISOString()`, which gives `YYYY-MM-DDTHH:MM:SS.mmmZ` in UTC. No `to_char` in SQL.
- `BIGINT` ids come back as strings (pg's default for int8) and go through `Number()`. `like_count` is `::int` in the SQL, so it is already a number.
- **A like on a missing post** is a foreign-key violation with SQLSTATE `23503`, which becomes 404 `post not found`. 201 vs 200 comes from `rowCount` (1 = inserted, 0 = `ON CONFLICT DO NOTHING`).
- The body length limit counts code points, which matches Postgres's `char_length()` in the CHECK constraint.

### Pool size at 1 core (measured)

One process on one core (`taskset -c 9`), 10,000 users over both ports, mload `-gzip`, 15 s ramp + 45 s hold, fresh DB, `DB_POOL` set explicitly:

| Load | pool 4 | pool 8 | pool 16 | pool 64 |
|---|---|---|---|---|
| `-think-scale 0.5` (~1,940 req/s, ~65% CPU) | p95 363 ms, all 4 busy | p95 28 ms | - | p95 25 ms, 105 backends opened for 64 slots (idle churn at the old 10 s timeout) |
| `-think-scale 0.35` (~2,600 req/s, core saturated) | p95 1,322 / 535 ms | p95 699 / 97 ms | p95 349 / 679 ms | p95 1,432 / 692 ms |

Below saturation, 4 connections are the bottleneck and 8 is as good as 64. At saturation the event loop's CPU is the limit and p95 is noise across pool sizes (two runs each). The single process tops out near 3,300 req/s, which 8 connections at ~600+ req/s each already cover. So the rule's `min(128, 8 x cores)` = 8 is used; the older 64 at 1 core only held idle connections.

## Second port (`EXTRA_PORT`)

Fastify listens on one port per `listen()` call (it only adds extra servers itself for a hostname that resolves to several addresses, like `localhost`). To serve a second port on the same app, this uses Fastify's documented **`serverFactory`** option. Fastify calls the factory with its request handler and its validated options. The factory builds the main `http.Server` from them and also keeps both. Once `app.listen()` resolves, a second `http.Server` is built the same way and listens on `EXTRA_PORT`. Requests on either port go through the same Fastify instance: same routes, hooks, compression and error handler. Fastify's `clientErrorHandler` (bound to the app, as Fastify does for the main server) is attached to the second server too.

With `serverFactory`, Fastify skips its own server setup (`getServerInstance` in `lib/server.js`, v5.12.7), so `makeServer` repeats exactly what that function does for HTTP/1, reading Fastify's options: `keepAliveTimeout` (120 s), `requestTimeout` (0), `setTimeout(connectionTimeout)` (0), and `maxRequestsPerSocket` only if > 0 (Fastify default 0, so Node's unlimited stays). Node's own `http.Server` defaults (`headersTimeout` 60 s, `connectionsCheckingInterval` 30 s, `noDelay`) are untouched on both, as they would be without the factory. `maxConnections` is Infinity on both. Both servers listen with backlog 65535. In cluster mode each worker calls `listen` on both ports, and the primary owns both listening sockets and hands out connections round-robin, the same as for one port. `app.close()` only closes the main server; nothing calls it (workers exit on SIGTERM).

Verified on the test box (4 workers): identical headers on both ports (`content-encoding: gzip`, `vary`, `Keep-Alive: timeout=120`, 404/401 JSON, 400 on a malformed request line), and 1,000 connections per port each served a gzipped `/feed`, sat idle 85 s, and all 2,000 served a second request.

## Other non-default settings (unchanged from std-node, reasons in its README)

`keepAliveTimeout` 120000 ms, `connectionTimeout` 0, `requestTimeout`/`headersTimeout` at their defaults (0 / 60 s), listen backlog 65535, `maxConnections` Infinity, `@fastify/compress` `{ global: true, encodings: ['gzip'] }` with the default 1 KB threshold, a response schema on every route, `--max-old-space-size` set to 70% of RAM ÷ workers (min 4096 MB) in `start.sh`, `NODE_ENV=production`, and logging off.

## Run

```bash
sudo bash install.sh     # Node 22 into /opt/node22 (idempotent, sha256-checked)
bash build.sh            # npm ci --omit=dev
DATABASE_URL=postgres://feed:...@db:5432/feed JWT_SECRET=million-users-challenge HOST=0.0.0.0 PORT=80 EXTRA_PORT=81 bash start.sh
```

## Test-box note

On the test box the ephemeral port range is 1024-65535. Another process's long-lived client connection can sit on a source port of 3110, and then a listener can't bind it (`EADDRINUSE`). That happened during testing: a keep-alive connection to another agent's server used local port 3110. This can't happen with 80/81 on the real box, because ports below 1024 are outside the ephemeral range.
