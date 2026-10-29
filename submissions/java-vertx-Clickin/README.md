# Java + Vert.x + PostgreSQL

| | |
|---|---|
| Language | Java 26, Temurin 26.0.2.1+1, which `install.sh` installs through SDKMAN. The compiler targets Java 21 bytecode |
| Framework | Vert.x Core 5.2.1 `HttpServer` without Vert.x Web, on Netty 4.2.19.Final with native epoll |
| Postgres driver | Vert.x PostgreSQL client 5.2.1 with prepared statements and pipelining |
| JSON | A hand-written byte writer for responses. Jackson Core 3.2.3, streaming only, parses request bodies and JWTs |
| GC | Generational ZGC with a fixed, pre-touched heap |
| Build | Maven 3.9.16 wrapper and the Shade plugin 3.6.1, producing `target/feed.jar` |

## Running it

```bash
sudo bash install.sh
bash build.sh
DATABASE_URL=postgres://feed:password@db:5432/feed JWT_SECRET=million-users-challenge \
  HOST=0.0.0.0 PORT=80 EXTRA_PORT=81 bash start.sh
```

Optional environment variables:

| Variable | Default | Effect |
|---|---|---|
| `MU_EVENT_LOOPS` | `nproc` | Number of event loops, one verticle each |
| `MU_DB_POOL` | `64` | Total Postgres connections, split over the loops, at most 512 |
| `MU_DB_PIPELINE` | `256` | Queries in flight per Postgres connection |
| `MU_GZIP_LEVEL` | `1` on 8 or more CPUs, else `0` | gzip level for the feed. `0` turns gzip off |
| `MU_HEAP` | Half of RAM | Java heap size |

## Design

**One verticle per event loop.** Each verticle listens on both ports and has its own pipelined `PgBuilder.client()`
with a pool event-loop size of 0. The same thread parses a request, runs its query, decodes the rows and writes the
response. Each verticle also keeps its own prepared queries, response buffer, HMAC and gzip state, so the loops share
nothing.

**Postgres.** The server runs the SPEC's SQL, one query per request. It prepares each query once per connection and
pipelines up to 256 queries on each of the 64 connections, so queries from many requests go out in one write.

**HTTP server.** Strict thread mode is on. The server never closes idle connections, which meets the SPEC's 75 s
minimum and removes Netty's per-connection idle timer. Compression, the h2c upgrade and WebSocket compression are off.
With them on, Vert.x creates an HTTP/2 initializer and two WebSocket deflate handshakers for every connection, about
120 MB at a million connections. A small `EpollTransport` subclass sets `TCP_DEFER_ACCEPT` on the listening sockets,
so the kernel hands over a connection only after its first request arrives.

**Allocation per request.** The server writes response JSON into a per-loop byte array and copies it once into a
buffer of the exact size. It uses no JSON generator and creates no intermediate strings. It formats `created_at`
directly from the `OffsetDateTime` that the driver returns, writes numbers two digits at a time, and bulk-copies
strings, switching to character-by-character escaping only at the first character that needs it. Header names and
values are encoded once at startup, `Content-Length` values come from a table, the code reads rows by column index,
and 200 responses skip `setStatusCode`. A feed response allocates about 7 KB less than it did with Jackson and
`java.time` formatting.

**JVM.** Each of a million connections keeps about 2.7 KB of live objects for the Netty channel, its pipeline and the
Vert.x connection, about 3 GB in total. These objects are new while users ramp in. ParallelGC and G1 paused for
140 to 830 ms per young collection at the end of the ramp. ZGC does this work concurrently. On Java 25 and later,
Netty 4.2 stops using `sun.misc.Unsafe` by default and falls back to `ByteBuffer` and `MemorySegment` access. That
path adds bounds and session checks to every buffer access and allocates a `DirectByteBuffer` per buffer, which was
17% of all allocation in a profile. `--sun-misc-unsafe-memory-access=allow` restores the `Unsafe` path.

**gzip on 8 or more CPUs.** The server compresses bodies of at least 1,024 bytes, which in practice means the feed,
at level 1 with a reused `Deflater`. On 4 cores gzip cost more CPU than it saved, so it is off below 8 CPUs.

## Measurements

I measured on Hetzner Cloud, not on the benchmark boxes. The app ran on a CCX33 (AMD, virtio NIC with one queue)
with all but 4 CPUs taken offline, and Postgres on a CPX52. Four load generators ran
`bench/mload -gzip -think-scale 2.5` with a 330 s ramp, about 3,000 new connections per second, and a 5-minute hold.

At 4 cores and 1,000,000 users the run did not pass. Once all users were connected, the server handled about 42k
requests per second at a p95 of about 240 ms and a p99 of about 270 ms, which is the load generators' own floor on
this setup. It used about 52% of the 4 cores, 20% user time and 32% kernel time. In the 1 to 2 minutes right after
the ramp, the full request rate, the last connection setups and the GC work on a million new connections overlapped.
CPU reached about 90% and p95 rose to 2 to 4 s. The best run finished with a p95 of 2.0 s, a p99 of 2.4 s and no
errors. I did not measure 8 or 16 cores.

These changes gave no gain at 1M users on 4 cores, so the submission does not use them: the io_uring transport, one
`SO_REUSEPORT` listener per event loop, batched event-loop wake-ups, 3 event loops instead of 4, and gzip on 4 cores.

## Checks

```bash
./mvnw -q -o -DskipTests clean package
bash src/test/check.sh
RECOVERY_DATABASE_URL=postgres://feed:feedpw@localhost:15432/feed_test bash src/test/recovery-check.sh
```

`check.sh` tests the `start.sh` defaults and the HTTP behaviour, compares the JSON writer with Jackson byte for byte,
and on Linux checks that the server reports why epoll is unavailable. `recovery-check.sh` checks that `/health`
recovers after Postgres comes back.
