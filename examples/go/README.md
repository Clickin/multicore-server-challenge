# std-go-pg: Gin + pgx/v5 pgxpool (Postgres)

The feed API (`SPEC.md`) on the stack a Go developer would normally ship, against Postgres 18.
Copied from the reviewed `servers/std-go` (SQLite); only the data layer changed, plus an optional second port.
Every HTTP/runtime setting below is the std-go one, unchanged.

| Piece | Version |
|---|---|
| Go | 1.27.1 (`/usr/local/go`), `CGO_ENABLED=0` |
| Web framework | github.com/gin-gonic/gin v1.12.0 on net/http (`http.Server`) |
| Compression | github.com/gin-contrib/gzip v1.2.8, `gzip.DefaultCompression` |
| DB driver | github.com/jackc/pgx/v5 v5.11.0, `pgxpool` used directly (native interface, not database/sql) |
| JWT | github.com/golang-jwt/jwt/v5 v5.3.1 (accepts the 184-bit HS256 key as is, no workaround) |

Env: `DATABASE_URL` (postgres://user:pass@host:5432/db, required), `JWT_SECRET`, `HOST`, `PORT`,
optional `EXTRA_PORT`, optional `DB_POOL`.

## Process / thread model
One process. net/http runs one goroutine per connection; the Go scheduler spreads them over
`GOMAXPROCS` OS threads. GOMAXPROCS is not set: the runtime takes it from the CPU affinity mask
(and, in Go 1.25+, the cgroup CPU limit), so `CPUAffinity`/`AllowedCPUs` = N gives N
(logged at startup; `taskset -c 8-11` gives 4). pgx does network I/O through the Go netpoller,
so a goroutine waiting on Postgres parks without holding a thread.

## DB pool
One `pgxpool.Pool`, `MaxConns` = min(64, 4 x GOMAXPROCS) (1c: 4, 2c: 8, 4c: 16, 8c: 32, 16c: 64),
overridable with `DB_POOL`. Single process, so this is also the app's total (<= 64, ~2-4 x the
Postgres box's 16 cores). Other pgxpool settings are defaults (MinConns 0, connections opened on
demand, MaxConnIdleTime 30 min, MaxConnLifetime 1 h, health check 1 min). Pool acquire timeout:
5 s via the per-request context (see table). Statements use pgx's default `QueryExecModeCacheStatement`
(each connection prepares a statement once and reuses it; extended protocol).

SQL is the spec's reference queries verbatim (`$1` placeholders, `::int` on like_count, feed `LIMIT $1`
with 20), one statement per request, autocommit. Like on a missing post: the foreign-key violation
(`*pgconn.PgError` with SQLSTATE `23503`) becomes 404 `post not found`; inserted-or-not comes from
the command tag's `RowsAffected()`.

`created_at` is scanned into `time.Time` (TIMESTAMPTZ) and formatted in the app:
`t.UTC().Format("2006-01-02T15:04:05.000Z")`. Go's fractional-second layout truncates (never
rounds), which is what the spec asks for.

## Non-default settings, and why
| Setting | Value | Why |
|---|---|---|
| `gin.SetMode(gin.ReleaseMode)` | release | production mode (no debug route dump / warnings) |
| `gin.New()` + `gin.CustomRecovery` | no Logger middleware | no access log; recovery returns the spec's `{"error":"internal server error"}` instead of an empty 500 |
| `gzip.Gzip(DefaultCompression)` | on | standard compression middleware; only applies when the client sends `Accept-Encoding: gzip` |
| Second port (`EXTRA_PORT`) | optional | if set, a second `http.Server` with identical settings and the same Gin engine `Serve()`s a second `net.Listener` (both bound before serving, so a bad port fails startup); one process, one pool. 3 load generators x 4 IPs x ~64k source ports is only ~770k connections per server port |
| `http.Server.IdleTimeout` (both servers) | 120 s | clients idle up to ~40 s between requests; default (falls back to ReadTimeout = none) is fine too, but an explicit bound >= 75 s reaps dead clients |
| `ReadHeaderTimeout` | 30 s | slow-header guard. Between keep-alive requests net/http first waits for the next request's bytes under `IdleTimeout` (`c.bufr.Peek(4)` in `conn.serve`, Go 1.27 `server.go`) and only then arms `ReadHeaderTimeout`, so it never cuts idle keep-alives. It does also bound the wait for the FIRST request after accept (30 s); mload sends right after connecting, so that is fine |
| `ReadTimeout` / `WriteTimeout` | unset | a ReadTimeout would also bound the idle wait if IdleTimeout were 0 |
| Connection cap | none | net/http has no connection limit |
| Listen backlog | kernel `net.core.somaxconn` | Go passes somaxconn to `listen()` (65535 on the test box); there is no per-listener knob in net/http |
| Request body | `http.MaxBytesReader` 16 KB | same limit as the reference implementation |
| Build | `CGO_ENABLED=0 go build -trimpath -ldflags='-s -w'` | release build (Go has no separate debug/release optimisation level); pgx is pure Go, so no cgo |
| DB call timeout | 5 s context per query | pool acquire timeout: pgxpool has no separate acquire-timeout option, `Acquire` honours the context deadline. The same deadline also bounds the statement; a timeout returns 500 |
| `GOMEMLIMIT` (start.sh) | 75% of MemTotal (~23.5 GiB on 31 GB) unless already set | Go's documented soft memory limit. With only GOGC=100 the GC goal is ~2x the live heap; at 1M connections the live heap is ~12 GB (see below), so the goal plus kernel socket memory would not fit in 31 GB. The limit makes the GC run more often near it instead of OOMing. Logged at startup |

Heap: Go has no heap cap by default, so nothing to raise; the opposite problem is that GOGC=100 alone lets
the heap double past the live set, hence `GOMEMLIMIT` above (review fix).

Per-connection memory is net/http's normal cost (a goroutine stack plus 4 KB bufio reader and
writer per connection); there is no standard knob to shrink it. Measured on the test box (gctrace, idle
connections after one gzip /feed each): ~10 KB live heap + ~2.5 KB goroutine stack per connection,
~29 KB RSS per connection at GOGC=100 without a limit. So ~12-13 GB live at 1M, plus kernel TCP
socket memory; fits in 31 GB with GOMEMLIMIT, marginal without.

Known cost, not changed (standard behaviour): gin-contrib/gzip takes a `gzip.Writer` from its
sync.Pool when the middleware runs, before the handler waits for a DB connection, and a Go
`compress/flate` writer is ~1 MB (measured: level 6 1050 KB, level 1 794 KB). At normal latency only
tens of requests are in flight (~tens of MB), but if the server saturates and requests queue for the
pool, memory grows ~1 MB per queued gzip request (100k requests fired at once OOM-killed it at 30 GB
in review). Changing the level barely helps; avoiding it would mean a different middleware.

`POST /posts` decodes with `encoding/json` rather than `c.ShouldBindJSON`, because the spec
separates "malformed JSON" (400 malformed) from an empty/missing body (400 body is required).

## Files
`install.sh` (Go 1.27.1 if missing), `build.sh` (-> `bin/stdgopg`), `start.sh` (exec, foreground, GOMEMLIMIT),
`main.go` (config, pool, servers), `handlers.go` (routes, auth, handlers).

## Results (the test box, server `taskset -c 8-11` (4 cores), Postgres 18 on the same box, fresh DB from `feed_seed`)
- `test.sh` on PORT 3103: `passed=42 failed=0`
- `test.sh` on EXTRA_PORT 3104 (another fresh DB): `passed=42 failed=0`
- smoke on 3103,3104: `RESULT users=3000 req/s=127 p95=2.2ms p99=21.9ms err=0.000% pass=true errors=map[]` (RSS ~110 MB, 7 pool connections open)

## Pool size change (after review)
Pool = min(128, 8 x cores) (was min(64, 4 x cores)): 8, 16, 32, 64, 128 at 1/2/4/8/16 cores. Each connection serves only ~600-1,600 req/s because every query waits on the network round trip to Postgres on the test box, and 1M users is ~42k req/s. The std-go-pg review measured at 4 cores: pool 16 -> 9.9k req/s (p95 218 ms), pool 32 -> 12.4k (p95 94 ms). Postgres allows 300 connections; most of these sit idle on the network.

## Diagnosis switches (2026-10-20, off by default)
Neither is part of the reviewed setup; they exist to test the explanations in `../../STANDARD.md`.
- `GZIP_MW=gzhttp`: wrap the whole engine in `klauspost/compress/gzhttp` (`MinSize(0)`, default level) instead of
  `gin-contrib/gzip`. gzhttp only takes a compressor when the response is written, so queued requests hold none. With
  gin-contrib/gzip, a hiccup at 350k users grew the Go heap from 2.6 GB to ~200 GB (mostly untouched pages, RSS ~17 GB),
  past GOMEMLIMIT, and GC took every core. With gzhttp, 350k held for 2 minutes with the heap flat at 3.5 GB. 42/42 checks.
- `TCP_KEEPALIVE=off`: `net.ListenConfig{KeepAlive: -1}`, i.e. no TCP keep-alive probes. Go turns them on by default
  (every 15 s per idle connection); the other stacks don't. At 350k (with gzhttp): p95 58.7 -> 6.2 ms, p99 206 -> 17 ms,
  CPU per request 368 -> 336 us (the probes contend on the single network queue's lock).
