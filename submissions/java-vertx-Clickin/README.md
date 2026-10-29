# Java + Vert.x + PostgreSQL

| | |
|---|---|
| Language | Java 26 (Temurin 26.0.2.1+1, installed by `install.sh` through SDKMAN), compiled to Java 21 bytecode |
| Framework | Vert.x Core 5.2.1 `HttpServer` (no Vert.x Web) on Netty 4.2.19.Final native epoll |
| Postgres driver | Vert.x PostgreSQL client 5.2.1: prepared statements, pipelining |
| JSON | Responses written byte by byte; Jackson Core 3.2.3 (streaming) parses request bodies and JWTs |
| GC | Generational ZGC, fixed pre-touched heap |
| Build | Maven 3.9.16 wrapper, Shade plugin 3.6.1 -> `target/feed.jar` |

## Running it

```bash
sudo bash install.sh
bash build.sh
DATABASE_URL=postgres://feed:password@db:5432/feed JWT_SECRET=million-users-challenge \
  HOST=0.0.0.0 PORT=80 EXTRA_PORT=81 bash start.sh
```

Optional env:

| Variable | Default | Effect |
|---|---|---|
| `MU_EVENT_LOOPS` | `nproc` | Event loops (one verticle each) |
| `MU_DB_POOL` | `64` | Postgres connections in total, split over the loops (at most 512) |
| `MU_DB_PIPELINE` | `256` | In-flight queries per Postgres connection |
| `MU_GZIP_LEVEL` | `1` on 8+ CPUs, else `0` | gzip level for the feed; `0` turns it off |
| `MU_HEAP` | half of RAM | Java heap |

## Design

**One verticle per event loop, nothing shared.** Each verticle listens on both ports and has its own pipelined
`PgBuilder.client()` (pool event-loop size 0). Parsing a request, running its query, decoding the rows and writing the
response all happen on the same thread. Prepared queries, the response buffer, the HMAC and the gzip state are per
verticle too.

**Postgres.** The SPEC's SQL, one query per request, prepared once per connection and pipelined: up to 256 queries in
flight on each of 64 connections, so queries from many requests go out in one write.

**HTTP server.** Strict thread mode. No idle timeout (the SPEC needs at least 75 s; connections are never closed),
which also removes Netty's per-connection idle timer. Compression, h2c upgrade and WebSocket compression are off:
otherwise Vert.x creates an HTTP/2 initializer and two WebSocket deflate handshakers for every connection, ~120 MB at a
million connections. The listening sockets use `TCP_DEFER_ACCEPT` (through a small `EpollTransport` subclass), so a
connection is accepted once its first request has arrived.

**Allocation per request.** The response JSON is written into a per-loop byte array and copied once into an
exact-size buffer. No JSON generator, no intermediate `String`s: `created_at` is formatted straight from the
`OffsetDateTime` the driver returns, numbers two digits at a time, and strings are bulk-copied, with a slow path only
from the first character that needs escaping. Header names and values are pre-encoded, `Content-Length` values come
from a table, rows are read by column index, and 200 responses skip `setStatusCode`. A feed response allocates ~7 KB
less than with Jackson and `java.time` formatting.

**JVM.** A million connections keep ~2.7 KB of live objects each (Netty channel and pipeline, Vert.x connection),
~3 GB in all, and they are new objects while the users ramp in. With ParallelGC and G1 that showed up as young pauses
of 140-830 ms at the end of the ramp; ZGC does the work concurrently. `--sun-misc-unsafe-memory-access=allow`: on
Java 25+ Netty 4.2 stops using `sun.misc.Unsafe` by default and goes through `ByteBuffer`/`MemorySegment` access,
with bounds and session checks on the hot path and a `DirectByteBuffer` per buffer (17% of all allocation in a
profile).

**gzip, feed only, 8+ CPUs.** Bodies of at least 1,024 bytes (in practice the feed) are compressed at level 1 with a
reused `Deflater`. On 4 cores it cost more CPU than it saved, so it is off below 8.

## Measurements

On Hetzner Cloud, not the benchmark boxes: the app on a CCX33 (AMD, virtio NIC with one queue) with CPUs taken
offline to leave 4 cores, Postgres on a CPX52, four load generators running `bench/mload -gzip -think-scale 2.5`,
ramp 330 s (~3,000 new connections/s), then a 5-minute hold.

**4 cores, 1,000,000 users: not passing there.** With everyone connected it serves ~42k req/s at p95 ~240 ms and p99
~270 ms (the load generators' own floor on that setup) using ~52% of the 4 cores (user ~20%, kernel ~32%). For 1-2
minutes right after the ramp, the full request rate, the last connection setups and the GC work on a million new
connections overlap, CPU reaches ~90% and p95 rises to 2-4 s (best run: p95 2.0 s, p99 2.4 s, 0 errors). 8 and 16
cores were not measured.

Tried and dropped, no gain at 1M on 4 cores: the io_uring transport, one `SO_REUSEPORT` listener per event loop,
batching event-loop wake-ups, 3 event loops instead of 4, gzip on 4 cores.

## Checks

```bash
./mvnw -q -o -DskipTests clean package
bash src/test/check.sh   # start.sh defaults, HTTP behaviour, JSON writer vs Jackson byte for byte, epoll failure cause on Linux
RECOVERY_DATABASE_URL=postgres://feed:feedpw@localhost:15432/feed_test bash src/test/recovery-check.sh   # /health recovers after Postgres comes back
```
