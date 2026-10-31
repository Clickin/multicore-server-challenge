# Java / Servlet 6.1 async on our own epoll loops

Front: direct

| | |
|---|---|
| Language / runtime | Java 25, Eclipse Temurin JDK `25.0.5+8` (official tarball, sha256-pinned per arch in `install.sh`), used to build and run |
| Web API | Jakarta Servlet 6.1 (`jakarta.servlet-api` 6.1.0 from Maven Central, sha256-pinned in `build.sh`): the app is one `HttpServlet` per resource (tags, users, profiles, articles), mapped by URL pattern, using the **async** API (`startAsync` / `AsyncContext.complete`) |
| Servlet container / HTTP | Our own: HTTP/1.1 on Linux `epoll` through FFM (`java.lang.foreign`), one event loop per thread, with servlet mapping (exact, `/prefix/*`, default), `HttpServlet` method dispatch (405, HEAD, OPTIONS) and an error servlet for `sendError` |
| Postgres driver | Our own v3 wire client: SCRAM-SHA-256, prepared statements, binary rows, pipelining, cross-request batching, group commit |
| JSON, JWT, bcrypt | Our own: JSON written straight from Postgres's binary rows, allocation-free HS256 verify, bcrypt cost 10 with interleaved lanes |
| Dependencies | `jakarta.servlet-api` (interfaces only). Build with `javac` only, no Maven/Gradle |

```bash
sudo bash install.sh     # Temurin 25 -> /opt/jdk
bash build.sh            # servlet API jar (pinned) + javac -> build/server.jar
DATABASE_URL=postgres://... JWT_SECRET=... HOST=0.0.0.0 PORT=80 bash start.sh
```

Linux only (it calls `epoll`, `eventfd` and `setpriority` directly).

## Idea

Postgres takes most of the box's CPU, so the server's job is (1) to make every request cheaper **for Postgres**
and (2) to cost as little CPU itself as it can, leaving the rest to Postgres. The application is still written
against the Servlet API: standard `HttpServlet`s, in async mode, on a container built for this workload.

## The container

- **Event loops, not threads per request.** `LOOPS` (default cores/2) platform threads, each with its own epoll set and
  its own `SO_REUSEPORT` listener (`TCP_DEFER_ACCEPT`, `TCP_NODELAY`). The loop parses the request and calls
  the container on its own thread, which maps the path to a servlet and calls its `service(req, res)`. A handler that needs the database calls `req.startAsync()`, submits
  the query and returns; the result comes back to the same loop (lock-free queue + `eventfd`), the callback writes the
  JSON and calls `complete()`, and the response goes out in one `write`. Nothing ever blocks a loop, so no virtual
  threads, no `ThreadLocal`s and no hand-offs to worker threads are needed.
- **No allocation in the steady state.** Request, response, `AsyncContext` and the app's per-request state are pooled
  per loop and recycled. Header fields, path and query are offsets into the read buffer; the standard getters
  (`getHeader`, `getParameter`, ...) still work but allocate only if called, and the hot paths don't call them. An idle
  connection is an fd and a small object: the read buffer is shared per loop, and a connection gets its own only
  while it holds a partial request or unsent output. Measured with JFR under load: a few hundred bytes per request at
  most, i.e. a young GC every minute or so with a 2 GB heap (ParallelGC, pre-touched).
- **Servlet mapping without a scan.** `Container.addServlet` takes exact, `/prefix/*` and default (`/`) patterns. A
  lookup hashes the path's first N segments once per registered depth, deepest first, into a table whose multiplier
  is searched at registration so patterns never collide, then checks the bytes: the cost does not grow with the number
  of servlets. Inside a servlet, `HttpServlet.service` picks `doGet`/`doPost`/... (a method it has no table for is its
  standard 405), and the op is one load from a table keyed by segment count and a keyword id (`WordTable`, hashed the
  same way). `sendError` renders through the error servlet, which writes the API's `{"errors":...}` JSON.
- **The rest of the Servlet API, lazily.** Readers and writers (with the request/response character encodings),
  cookies, locales, remote and local addresses (`getpeername`/`getsockname` on first call), `Host`-based server name
  and URL, `HttpServletMapping`, dispatcher types (ERROR inside the error servlet, ASYNC after `AsyncContext.dispatch()`),
  `AsyncContext.start` (a pooled task on the request's own loop, so the runnable must not block) and async listeners.
  None of it runs unless called. Not provided: sessions, login, multipart, request dispatching to other paths,
  protocol upgrade and trailers; those throw or answer as the spec says for a container without them. The async
  timeout is stored but not enforced, and a response is sent whole on completion (`flushBuffer` cannot commit early).
- **Backpressure.** At most `MAX_INFLIGHT_PER_LOOP` (1024) requests in flight per loop; past that a loop stops reading
  new requests until one completes, so overload raises latency instead of memory.

## Postgres work per request

- **One statement per request**, prepared once per connection, binary results, generic plans that stay sane: every
  list's page is cut first (`LIMIT/OFFSET` on the bare index walk) and decorated afterwards, and the page sits under a
  constant `LIMIT 100` so a parameter `LIMIT` can't make the planner expect 10% of the table (it once picked a hash
  join over all 500k articles for every request in a batch: 8 s per batch, 13 ms after).
- **Reads are batched across requests.** A single dispatcher thread owns all read connections (8) and write
  connections (4). Each read connection has one round in flight; reads that arrive meanwhile wait (and wait up to
  `READ_GATHER_US` = 5 ms for company), then go out together: requests of a kind become **one** statement over arrays
  of their parameters (`unnest(...) WITH ORDINALITY` + `LATERAL`), each row tagged with its request. Within a
  statement, each distinct article is decorated (tags, favorites count, author) **once**, however many requests in
  the batch show it; only `favorited` / `following` are per viewer. Nothing outlives the statement (rule 5).
- **The feed's count, proven instead of counted.** Most viewers follow an author with 1,000+ articles (82.6% of users),
  so their capped count is 1,000. A batch picks the 4 authors most followed by its viewers, proves ≥ 1,000 articles
  for each with one bounded index walk, and only the other viewers count.
- **Group commit.** Each write connection has one group in flight; writes that arrive meanwhile go out together, each
  request's statements back to back and one `Sync` at the end: one transaction, one WAL flush. Responses are sent
  only after the group commits (rule 6). If any statement fails, the group is rolled back by Postgres and its
  requests are retried one by one, so only the faulty one gets its error (e.g. 422 on a duplicate email).

Measured locally on the full seed (Postgres, server and load generator in one 8-CPU container, fixed load of
18k req/s; `pg_stat_statements`, local only):

| Step | Postgres CPU |
|---|---|
| per-request statements, pipelined | ~1.9 cores |
| + batching with 5 ms gather | ~1.3 |
| + every read kind batched, feed count shortcut, decoration once per article | ~1.0 – 1.4 (noisy) |

## bcrypt

Every login and sign-up verifies or hashes with cost 10 (rule 7). That is about 28 hashes/s at 100k users and, at
~65 ms per hash on EPYC, ~1.7 cores: a fifth of the box. Blowfish is one long dependency chain, so a single hash
leaves most of the core idle: `BCrypt` runs up to 4 independent hashes interleaved in the same loop (1 lane 67.5 ms,
4 lanes 38 ms per hash on EPYC). Hashes run on their own `BCRYPT_THREADS` (4) threads at `nice` 5 (`setpriority`
per thread through FFM; `Thread.setPriority` is a no-op on Linux), which wait up to 40 ms to fill their lanes.

## Results

On Hetzner **ccx33** (8 dedicated AMD EPYC vCPU; Postgres configured by `box/pg-conf.sh` as for 16 GB), full seed,
the repo's `loadgen search` from two separate load boxes over a private network:

| Phase | Users | req/s | p50 | p95 | p99 | Errors | Box CPU: app / Postgres |
|---|--:|--:|--:|--:|--:|--:|---|
| probe | 70,000 | 18,463 | 23 ms | 40 ms | 49 ms | 0% | 26% / 37% |
| probe | 85,000 | 22,363 | 29 ms | 53 ms | 65 ms | 0% | 31% / 43% |
| probe | 95,000 | 24,926 | 39 ms | 76 ms | 102 ms | 0% | 34% / 49% |
| 5-minute hold | 95,000 | 24,853 | 45 ms | 97 ms | 124 ms | 0% | 36% / 55% |
| probe | 105,000 | 27,354 | 64 ms | 139 ms | 188 ms | 0% | 36% / 55% |
| **5-minute hold** | **105,000** | **27,235** | **69 ms** | **159 ms** | **234 ms** | **0%** | **37% / 58%** |

The 105,000 hold passed with 0 errors and the box at ~96% CPU, so this is close to where the box itself runs out.
The loadgen marked that hold "LG-limited" (one load box's sampler tick was 3.7 s late once): the load boxes (16
shared vCPU, the account's limit) were at their edge too, so the strict reading is a clean 95,000 hold and a
105,000 hold that passed with load-box warnings. This is not the official box, so compare with the leaderboard loosely.

## Correctness checks beyond the suite

- `test/run.sh`: 197/197.
- `dev/diffcheck.py`: 3,000 concurrent reads against a batching server and one with `COALESCE=0` on the same
  database: byte-identical responses.
- `test/sql/batch_equiv.sql` and `test/sql/feed_check.sql`: the batched statements return exactly the rows of the
  per-request ones, and the feed shortcut the counts of the reference SQL (2,000 viewers).
- `test/run-unit.sh`: wire-protocol parsing (fragmented messages, errors, group retry), JWT edge cases, bcrypt
  vectors across lanes, JSON escaping, timestamp formatting, slugify.

## Tuning knobs (all optional; defaults are what was measured)

`LOOPS`, `READ_CONNS` (8), `WRITE_CONNS` (4), `READ_GATHER_US` (5000), `READ_ROUND_MAX` (512), `WRITE_GROUP_MAX` (256),
`COALESCE` (1), `MAX_INFLIGHT_PER_LOOP` (1024), `BCRYPT_THREADS` (4), `BCRYPT_LANES` (4), `BCRYPT_GATHER_MS` (40),
`BCRYPT_NICE` (5), `JAVA_OPTS`.

`dev/` holds the local Linux box (`Dockerfile`, `conformance.sh`, `load.sh`, `alloc.sh`) and the Hetzner scripts
(`dev/hcloud/`). They aren't used by the benchmark.

## Credits

The FFM epoll/socket bindings and the Postgres wire-protocol basics started from
[java-epoll-stohirov](https://github.com/arjaythedev/million-users-challenge/tree/main/submissions/java-epoll-stohirov)
(MIT) in the million-users challenge. Ideas borrowed from this repo's leaders: array-parameter batching and the feed count shortcut from [zig-iouring-nick-potts](../zig-iouring-nick-potts)
([#79](https://github.com/arjaythedev/multicore-server-challenge/pull/79),
[#117](https://github.com/arjaythedev/multicore-server-challenge/pull/117)); gathering reads and one round per
connection from [scala-netty-didierliauw](../scala-netty-didierliauw)
([#75](https://github.com/arjaythedev/multicore-server-challenge/pull/75),
[#125](https://github.com/arjaythedev/multicore-server-challenge/pull/125)).

MIT, under the repo's [license](../../LICENSE).

Built with Claude Code (Anthropic). Changes were checked against the unit tests (`test/run-unit.sh`), the
conformance suite (`dev/conformance.sh`) and alternating local load runs against the previous build (`dev/load.sh`).
