# std-rust-pg: axum + sqlx (Postgres)

The feed API from the challenge `SPEC.md`, written the way a typical Rust team would ship it: axum on a
multi-thread tokio runtime, sqlx's `PgPool`, `jsonwebtoken`, and tower-http's gzip middleware. No
custom HTTP code, no `unsafe`, no response cache, no batching. A copy of the reviewed `std-rust` (SQLite)
with only the data layer swapped for Postgres (as in the older `servers/rust`) and a second listen port.

## Stack

| Piece | Version |
|---|---|
| Rust | 1.94.0 (stable, rustup in `/opt/twelve-rust`), edition 2024 |
| axum | 0.8.9 (default features) |
| hyper / hyper-util | 1.11.1 / 0.1.21 (via `axum::serve`) |
| tokio | 1.53.1, multi-thread runtime |
| tower-http | 0.6.11, `CompressionLayer` (gzip, async-compression 0.4.51 + flate2 1.1.10) |
| sqlx | 0.9.0, `postgres` + `chrono` features (sqlx-postgres 0.9.0, pure Rust, no TLS) |
| chrono | 0.4.45 (`TIMESTAMPTZ` -> `DateTime<Utc>`) |
| Postgres server | 18 (separate box) |
| jsonwebtoken | 11.1.0, `rust_crypto` backend |
| serde_json | 1.0.151 |

Exact versions are pinned in `Cargo.lock` (`cargo build --locked`).

## Process and thread model

- **One process.** tokio's multi-thread runtime with `worker_threads = available_parallelism()`. On Linux
  that call uses `sched_getaffinity` (and cgroup CPU quotas), so systemd `CPUAffinity`/`AllowedCPUs` or
  `taskset` sets the worker count: 1, 2, 4, 8 or 16. This is tokio's own default; it is set explicitly
  only so the value is visible and logged at startup.
- **One tokio task per connection** (`axum::serve`), work-stealing across the workers.
- **DB pool:** one `PgPool` for the whole app (single process), `max_connections = min(128, 8 x cores)`:
  8/16/32/64/128 for 1/2/4/8/16 cores; env `DB_POOL` overrides it. Async driver: queries run on the tokio
  workers, no extra threads. `acquire_timeout` 5 s; other pool options are sqlx defaults (min 0
  connections, so the pool grows on demand; idle timeout 10 min, max lifetime 30 min, fair FIFO
  acquire queue, `test_before_acquire = true`, statement cache 100 per connection, so each query is
  prepared once per connection and then runs as a cached extended-protocol statement). When the pool is
  exhausted, requests wait in sqlx's queue (up to 5 s, then `PoolTimedOut` -> 500 JSON); measured with
  `DB_POOL=2` and 256 concurrent `/feed` clients: 2,366 req/s, p99 129 ms, zero errors.
- **SQL:** `SPEC.md` "## SQL" verbatim (`$1` placeholders, `::int` on `like_count`), one statement per
  request, autocommit. Like on a missing post: SQLSTATE `23503` (foreign_key_violation) -> 404
  `post not found`; inserted-or-not from `rows_affected()`.
- **created_at:** formatted in the app: sqlx decodes `TIMESTAMPTZ` to `chrono::DateTime<Utc>`, then
  `format("%Y-%m-%dT%H:%M:%S%.3fZ")`. chrono's `%.3f` truncates the microseconds to milliseconds (no rounding).
- **Two ports:** `PORT`, plus `EXTRA_PORT` if set. Each gets its own `TcpSocket` listener (same backlog,
  `TCP_NODELAY`) and its own `axum::serve(listener, app.clone().into_make_service())` task, spawned on the
  same runtime: same router, state and pool, one process.

## Non-default settings, and why

| Setting | Value | Why |
|---|---|---|
| Pool size | `min(128, 8 x cores)`, env `DB_POOL` | Orchestrator rule for all -pg apps (raised from min(64, 4 x cores)): each connection serves only ~600-1600 req/s because of the ~0.25 ms network round trip, and 1M users need ~42k req/s. sqlx's default is 10 regardless of cores. |
| Pool acquire timeout | 5 s | Brief (sqlx default 30 s). |
| `start.sh`: `ulimit -n "$(ulimit -Hn)"` | soft open-files limit = hard limit | No-op under systemd (`LimitNOFILE` sets both). From a login shell the soft limit is 1024 and tokio does not raise it (Go's runtime and the JVM do), so past ~1,020 connections axum's accept loop hit EMFILE, slept 1 s per retry (silently: the error goes to `tracing` with no subscriber) and the rest sat in the kernel accept queue. That was the builder's 3,000-user smoke `req/s=48` (see below). |
| `EXTRA_PORT` | second listener | Brief: one server port caps at ~770k connections from 3 load generators x 4 IPs. |
| Listen backlog | 65535 (kernel caps it at `net.core.somaxconn`, 65535 on the boxes) | `tokio::net::TcpListener::bind` hard-codes 1024. The socket is built with `tokio::net::TcpSocket` (`SO_REUSEADDR`, `listen(65535)`) and handed to `axum::serve`. |
| `axum::serve(listener, app.into_make_service())` | instead of `axum::serve(listener, app)` | Both are documented axum API. With the bare `Router`, axum's `Service<IncomingStream>` impl calls `Router::with_state(())` for every accepted connection, which rebuilds the whole route table (HashMap + one `MethodRouter`/`Fallback` clone per route, ~20 heap blocks, ~5.6 KB) and keeps it for the connection's lifetime. `into_make_service()` finalizes the router once and gives each connection an `Arc` clone. Measured: 8.3 KB -> 1.7 KB per just-accepted connection, 33.5 -> 27.8 KB per idle connection with gzip. |
| `TCP_NODELAY` | on (`ListenerExt::tap_io`, the pattern from axum's docs) | Standard for request/response APIs; avoids Nagle/delayed-ACK stalls. |
| Compression | `tower_http::compression::CompressionLayer::new()`, gzip feature only | Brief: clients send `Accept-Encoding: gzip`. Default level and default predicate (bodies over 32 bytes). Requests without `Accept-Encoding` get plain JSON. |
| Release profile | `lto = "fat"`, `codegen-units = 1`, `RUSTFLAGS=-C target-cpu=native` | Common production profile; the benchmark boxes are identical CPUs (Xeon 8260), so a native build is safe. |

Left at defaults on purpose:

- **Keep-alive idle timeout: none.** axum 0.8's `serve` builds a hyper-util `auto::Builder` without
  installing a hyper timer, so hyper's `header_read_timeout` (30 s default) is inactive (`Time::check`
  returns `None` when no timer is set) and HTTP/1.1 keep-alive connections stay open indefinitely while
  idle. Verified on the test box: a connection idle for 100 s served its next request normally. That satisfies
  the >= 75 s requirement with no setting.
- **No connection cap.** `axum::serve` has none; the limit is `LimitNOFILE` (4194304 under systemd). If
  `accept` fails (e.g. EMFILE), axum emits a `tracing` error (silent: no subscriber) and retries after 1 s.
- **No access log.** No tracing subscriber is installed and there is no `TraceLayer`. Only 500 errors are
  printed to stderr.
- **JWT:** `jsonwebtoken::Validation::new(HS256)`: signature, `exp` required and checked (default 60 s
  leeway), HS256 only. jsonwebtoken accepts the 184-bit secret without complaint, so no workaround.
- hyper's HTTP/1 buffers (8 KB read buffer that ends up 16 KB, see below; 8 KB write buffer; ~400 KB max)
  and HTTP/2 support (axum default features) are untouched; `axum::serve` exposes no hyper builder options.

## Running

```bash
./install.sh   # root: build-essential + curl if missing, Rust 1.94.0 in /opt/twelve-rust if missing
./build.sh     # cargo build --release --locked (target-cpu=native)
DATABASE_URL=postgres://user:pass@host:5432/db JWT_SECRET=million-users-challenge \
  HOST=0.0.0.0 PORT=80 EXTRA_PORT=81 ./start.sh          # optional: DB_POOL=<n>
```

The process needs a high open-files limit (systemd `LimitNOFILE=4194304`): every client connection is a
file descriptor.

## Measured on the test box (`taskset -c 8-11`, 4 tokio workers, pool 32, Postgres 18 on the same box)

- `test/test.sh` on PORT 3925: `passed=42 failed=0`; on EXTRA_PORT 3926 (another fresh DB): `passed=42 failed=0`.
  Both ports: `Content-Encoding: gzip` on `/feed`; a row inserted with `created_at = ...59.999999+00` is served
  as `...59.999Z` (truncated); like on a missing post -> 404 `post not found`.
- Smoke (3,000 users over both ports, gzip, think-scale 2.5), started from a shell with soft nofile 1024:
  `RESULT users=3000 req/s=127 p95=2.8ms p99=5.3ms err=0.000% pass=true errors=map[]`; server RSS 88 MB
  at 3,000 connections.
- The first smoke (before the `ulimit` line in `start.sh`) gave `req/s=48` vs ~127 for every other stack.
  Reproduced with soft nofile 1024: the process sat at exactly 1,024 fds, 1,500 connections were
  ESTABLISHED per port, but 996 per port were still in the listen socket's accept queue (`ss -ltn` Recv-Q)
  for the whole hold; their users' requests never completed within the run (60 s client timeout, so no
  fails were recorded). Same binary with nofile 4194304: 3,020 fds, accept queues 0, `req/s=127`.
- 2,000 idle keep-alive connections (both ports) all served a request after 85 s idle.
- `test_before_acquire` (sqlx default `true`: a Sync/ReadyForQuery round trip on every acquire from the idle
  queue) is kept. A/B on loopback, one client connection, `/posts/{id}`: p50 0.262 ms with it vs 0.209-0.220 ms
  without (3,519 vs 4,187-4,445 req/s), i.e. one extra round trip per request; at 256 concurrent clients the
  box was too noisy (other agents' load) to separate the two. On the real network it costs ~0.25 ms per
  request and halves per-connection throughput; at 128 connections the pool still allows well over 100k
  req/s against the ~42k needed, so the library default stays.
- No SQLite left: no PRAGMAs, no `LIBSQLITE3_FLAGS`, libsqlite3-sys is not built (only in `Cargo.lock` as an
  unused optional sqlx dependency). The binary links only glibc/libgcc.

Connection memory (HTTP side) is unchanged from `std-rust`: see its README (~28 KB per idle keep-alive
connection with gzip; 16 KB hyper read buffer + 8 KB write buffer + task/connection state).
