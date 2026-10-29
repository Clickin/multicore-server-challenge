# Java + Vert.x + PostgreSQL

| | |
|---|---|
| Language | Java 26 (Temurin 26.0.2.1+1, installed by `install.sh` through SDKMAN); compiled to Java 21 bytecode |
| Framework | Vert.x Core 5.2.1 (`HttpServer`, no Vert.x Web), Netty 4.2.19.Final native epoll |
| Postgres driver | Vert.x PostgreSQL client 5.2.1, pipelined, prepared statements |
| JSON | Hand-written byte writer for responses; Jackson Core 3.2.3 (streaming only) parses request bodies and JWTs |
| GC | Generational ZGC, fixed pre-touched heap (half of RAM) |
| Build | Maven 3.9.16 wrapper, Shade plugin 3.6.1, `target/feed.jar` |

The question this entry asks: how far can a mainstream framework go, without writing our own event loop or Postgres
wire client?

## Running it

```bash
sudo bash install.sh
bash build.sh
DATABASE_URL=postgres://feed:password@db:5432/feed JWT_SECRET=million-users-challenge \
  HOST=0.0.0.0 PORT=80 EXTRA_PORT=81 bash start.sh
```

Optional overrides, for experiments only:

| Variable | Default | Effect |
|---|---|---|
| `MU_EVENT_LOOPS` | `nproc` | Event loops = verticle instances |
| `MU_DB_POOL` | `64` | Postgres connections in total, split over the loops (at most 512) |
| `MU_DB_PIPELINE` | `256` | In-flight queries per Postgres connection |
| `MU_GZIP_LEVEL` | `1` on 8+ CPUs, else `0` | Feed gzip level; `0` turns it off |
| `MU_HEAP` | half of `MemTotal` | Java heap |

## Design

**One verticle per event loop, nothing shared.** Each verticle listens on both ports and owns its own pipelined
`PgBuilder.client()` (pool event-loop size 0), so a connection's HTTP parsing, its query, the result decoding and the
response all run on one thread with no handoff. It also owns its prepared queries, response scratch buffer, HMAC and
gzip state.

**Postgres.** The SPEC's SQL, one query per request, prepared once per connection and pipelined (up to 256 in flight
per connection, 64 connections). Many requests' queries share one write to Postgres, which keeps the app box at
about 6 packets per request.

**HTTP server options.** Strict thread mode on. Idle timeout off (the SPEC asks for at least 75 s; we never close),
which also drops Netty's per-connection `IdleStateHandler` timer. Compression, h2c upgrade and WebSocket compression
off: with them on, Vert.x builds an `Http2CodecServerChannelInitializer` and two WebSocket deflate handshakers for
every connection, ~120 MB at a million connections. `TCP_DEFER_ACCEPT` on the listening sockets (set through a small
`EpollTransport` subclass), so a connection is accepted once its first request has arrived.

**Allocation per request.** The response JSON is written byte by byte into a per-loop scratch array, copied once into
an exact-size buffer: no Jackson generator, no `String` for `created_at` (formatted straight from the
`OffsetDateTime` the driver returns), two-digit number writing, bulk-copied strings with a slow path only from the
first character that needs escaping. Header names and values are pre-encoded, `Content-Length` values come from a
table, rows are read by position, and 200 responses skip `setStatusCode`. This took ~7 KB off each feed response and
cut steady-state CPU at a million users from ~70% to ~52% of 4 cores.

**JVM.** ZGC with a fixed, pre-touched heap. A million connections keep ~2.7 KB of live objects each (the Netty
channel and pipeline, the Vert.x connection), ~3 GB in all, and new connections age through the young generation
during the ramp. With ParallelGC and G1 the young pauses grew to 140-830 ms at the end of the ramp; ZGC does that work
concurrently. `--sun-misc-unsafe-memory-access=allow` lets Netty 4.2 use `sun.misc.Unsafe` again: on Java 25+ it
turns it off by default and falls back to `ByteBuffer`/`MemorySegment` access (bounds and session checks in the hot
path, and a `DirectByteBuffer` object per buffer, 17% of all allocation in a profile).

**gzip, feed only, 8+ CPUs.** Bodies of at least 1,024 bytes (the feed) are compressed with a reused `Deflater` at
level 1. On 4 vCPUs it cost more user CPU than it saved in kernel time, so it is off below 8 CPUs.

## Measurements

Not the benchmark boxes: Hetzner Cloud, app on a CCX33 (AMD, dedicated vCPUs, virtio NIC with one queue) with CPUs
taken offline to get 4 cores, Postgres on a CPX52, four load generators running `bench/mload -gzip -think-scale 2.5`
over 6 IPs × 2 ports, ramp 330 s (~3,000 new connections/s) then a 5-minute hold.

- **4 cores, 1,000,000 users: not passing yet.** Once everyone is connected the server holds the million at
  ~42k req/s with p95 ~240 ms and p99 ~270 ms (the load generators' own floor here) using ~52% of the 4 cores
  (user ~20%, kernel ~32%). But for 1-2 minutes right after the ramp ends, when the full request rate, the last
  connection setups and the GC work for a million young connections overlap, CPU reaches ~90% and p95 jumps to
  2-4 s, which fails the run as a whole (best run: p95 2.0 s, p99 2.4 s, 0 errors).
- 8 and 16 cores were not measured on this setup.
- For scale: on the same 4-core box, a framework-free entry (`java-epoll-stohirov`) passes with ~42% CPU. Its
  kernel time per request is close to ours; the difference is user time and, above all, its 72-byte connection
  object versus Vert.x/Netty's ~2.7 KB, which is what makes the end of the ramp expensive here.

Tried and dropped (no gain at 1M on 4 cores): io_uring transport, one `SO_REUSEPORT` listener per event loop,
event-loop wake-up batching, 3 event loops instead of 4, gzip on 4 cores, ParallelGC and G1.

## Checks

```bash
./mvnw -q -o -DskipTests clean package
bash src/test/check.sh   # start.sh defaults, core HTTP behaviour, JSON writer vs Jackson byte for byte, epoll failure on Linux
RECOVERY_DATABASE_URL=postgres://feed:feedpw@localhost:15432/feed_test bash src/test/recovery-check.sh   # health recovers after the database comes back
```

The official suite: `bash test/run.sh submissions/java-vertx-Clickin` from the repo root (42/42).
