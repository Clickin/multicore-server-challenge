# std-java-pg: Feed API on standard Spring Boot over Postgres (million-users run)

This is `std-java` (stock Spring Boot 3: Spring MVC on embedded Tomcat with virtual threads, JdbcTemplate over HikariCP, Jackson, Auth0 java-jwt) with only the data layer swapped from xerial sqlite-jdbc to pgjdbc, plus an optional second listening port (`EXTRA_PORT`). Every web/JVM setting from the reviewed `std-java` is unchanged. SQL is the spec's Postgres SQL verbatim (`$1` -> `?`, `::int` on like_count, feed `LIMIT ?` bound to 20). It passes the challenge test suite with `passed=42 failed=0` on both ports.

## Stack (exact versions, from the built jar)

| Piece | Version |
|---|---|
| Java | OpenJDK 21.0.12 (Ubuntu 24.04 `openjdk-21-jdk-headless`) |
| Spring Boot | 3.5.16 (latest 3.5.x), `spring-boot-starter-web` + `spring-boot-starter-jdbc` |
| Spring Framework / MVC | 6.2.19 |
| Embedded Tomcat | 10.1.55 (NIO connector) |
| Jackson databind | 2.21.4 |
| HikariCP | 6.3.3 |
| pgjdbc (org.postgresql:postgresql) | 42.7.11 (managed by Spring Boot 3.5.16) |
| Postgres server | 18 (on another box, `DATABASE_URL`) |
| JWT | com.auth0:java-jwt 4.6.2 |
| Maven | 3.9.16 via the checked-in wrapper (`./mvnw`) |

## Process / thread / pool model

- There is **one JVM process**, and it uses every core the process is allowed to run on. `Runtime.availableProcessors()` reads the affinity mask (`sched_getaffinity`) that systemd's `CPUAffinity`/`AllowedCPUs` sets, not the machine's total.
- **Virtual threads** (`spring.threads.virtual.enabled=true`). Tomcat's NIO poller accepts and polls the sockets, and each request runs on its own virtual thread. The carrier ForkJoinPool has one thread per usable core. No worker-thread cap applies. Tomcat 10.1's NIO connector has exactly one Acceptor thread and one Poller thread (the old `pollerThreadCount`/`acceptorThreadCount` knobs are gone), so at high core counts the single poller thread, which also scans every selection key about once a second for timeouts, is a possible ceiling. There's no standard setting to change that. An idle keep-alive connection holds no thread, virtual or otherwise, only its socket wrapper and buffers.
- **pgjdbc on virtual threads.** pgjdbc 42.6+ replaced its `synchronized` blocks with `ReentrantLock`, so a virtual thread waiting on the socket for a Postgres reply unmounts and frees its carrier. Waiting for a pooled connection is fine too: Hikari's `ConcurrentBag` waits on a `SynchronousQueue`, which unmounts the virtual thread, for up to 5 s. Checked in the pinned jars (javap): pgjdbc 42.7.11's `QueryExecutorImpl` takes `ResourceLock` (a `ReentrantLock`) around every query; its only `synchronized` blocks guard the binary-OID sets (no I/O). `PgConnection`, `PgStatement`, `PgPreparedStatement`, `PGStream` have none. Hikari 6.3.3's `ConcurrentBag` has none; `ProxyConnection`'s synchronized statement tracking does no I/O. A 3000-user smoke with `-Djdk.tracePinnedThreads=short` logged no pinning besides the one-time pool start below.
- Tomcat closes the connection after any 400, 408, 411, 413, 414, 500, 501 or 503 response (`Http11Processor.statusDropsConnection`, not configurable). So a 500 (e.g. a pool acquire timeout) costs the client a reconnect.
- **One HikariCP pool** with `maximumPoolSize = DB_POOL` if set, else `min(128, 8 x cores)`, set in `DataSourceConfig`. One JVM, so that is the app's total Postgres connection count: 8 / 16 / 32 / 64 / 128 at 1 / 2 / 4 / 8 / 16 cores (verified in `pg_stat_activity`: 32 at `taskset -c 8-11`, 64 at `8-15`, 20 with `DB_POOL=20`). The formula was raised from min(64, 4 x cores) for all -pg apps because each connection only serves ~600-1600 req/s (one network round trip per query), and 1M users is ~42k req/s. `minimumIdle` defaults to the max (pool fully pre-filled once started), and the acquire timeout is 5 s. Virtual threads queue in Hikari when every connection is busy. Hikari defaults are kept: max-lifetime 30 min (each connection is retired at a slightly randomized age and replaced in the background), keepalive-time 2 min, validation through JDBC4 `isValid()`. The pool starts on the first `getConnection()` (Boot builds `HikariDataSource` with the no-arg constructor, as its own auto-configuration does), so the very first request opens the first Postgres connection inside a `synchronized` block (a one-time carrier pin, ~0.4 s, seen with `-Djdk.tracePinnedThreads`); the housekeeper then fills the rest. The deploy's `/health` probe takes that hit before load arrives.
- `DATABASE_URL` (`postgres://user:pass@host:port/db`) is translated to `jdbc:postgresql://host:port/db` plus username/password in `DataSourceConfig`. pgjdbc defaults otherwise (extended protocol, server-side prepared statements after 5 uses: `PGProperty.PREPARE_THRESHOLD` default "5" in 42.7.11).
- **Two ports.** `PORT` is Spring Boot's main connector. If `EXTRA_PORT` is set, `TomcatConfig` adds a second Tomcat `Connector` with `factory.addAdditionalTomcatConnectors(...)` (Spring Boot's documented way to listen on two ports). Boot applies its `server.*` settings only to the main connector, so the extra one is given the same: the same NIO protocol and `server.address`, every registered connector customizer (max-connections, accept-count, keep-alive-timeout, connection-timeout, max-keep-alive-requests, and the socket buffers), every protocol-handler customizer (the virtual-thread executor), and `server.compression` applied the way Boot's package-private `CompressionConnectorCustomizer` does. The customizer runs at `LOWEST_PRECEDENCE`, after Boot's own customizers have registered theirs. Same JVM, same Spring context, same Hikari pool, same carrier threads; each connector has its own Acceptor and Poller thread. Verified at runtime (review): with `server.tomcat.mbeanregistry.enabled=true`, all 172 attributes of the `Connector`, `ProtocolHandler`, `ThreadPool` and `SocketProperties` MBeans of both connectors are identical except the port/name (maxConnections 2000000, acceptCount 8192, keepAliveTimeout/connectionTimeout/soTimeout 90000, maxKeepAliveRequests -1, compression on / application/json / 2048, maxHttpHeaderSize 8192, appRead/WriteBufSize 4096, URIEncoding UTF-8, maxThreads -1 = external executor); the heap holds two Tomcat `VirtualThreadExecutor`s and no platform `ThreadPoolExecutor`; 404/405/415/400/401/FK-404 JSON bodies and gzip are byte-identical on both ports.
- `created_at` (TIMESTAMPTZ, microseconds) is read as `OffsetDateTime` -> `Instant` and formatted in the app by Jackson with `@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")`. `SSS` prints the first three fraction digits, so it truncates: a row stored as `18:32:46.622778` comes out `18:32:46.622Z`.
- Like on a missing post: Postgres raises SQLSTATE `23503` (foreign_key_violation), which Spring wraps in `DataIntegrityViolationException`; that maps to 404 `post not found`. Inserted-or-not comes from the update count of `ON CONFLICT DO NOTHING`.
- Each request runs one SQL statement in autocommit, using the spec's reference queries.

## Non-default settings and why

| Setting | Value | Why |
|---|---|---|
| `spring.threads.virtual.enabled` | true | Blocking JDBC on virtual threads. Concurrency isn't limited by Tomcat's 200-thread pool. |
| `server.tomcat.max-connections` | 2000000 | The default is 8192. Spring Boot ignores `-1`, so this is a large explicit cap above 1M. |
| `server.tomcat.accept-count` | 8192 | The listen backlog (default 100), for connection bursts during ramp-up. |
| `server.tomcat.keep-alive-timeout` | 90s | The default falls back to connection-timeout (20 s). Clients idle up to ~40 s, and the brief asks for >= 75 s. |
| `server.tomcat.connection-timeout` | 90s | The same idle allowance before the first request on a new connection. |
| `server.tomcat.max-keep-alive-requests` | -1 | The default of 100 closes each connection after 100 requests. Unlimited means no reconnect churn. |
| `server.compression.enabled` + `mime-types=application/json` | on | Standard gzip when `Accept-Encoding: gzip` is sent. min-response-size stays at the 2 KB default, but Tomcat only applies it when the Content-Length is known. Spring MVC's Jackson converter streams without a Content-Length (chunked), so every JSON body is gzipped when the client asks, including small ones and 4xx errors (verified with curl: `/feed`, `/posts/1`, 400, 401, 404 all come back `Content-Encoding: gzip`). That's the stock Spring Boot behavior and it decodes fine; it just spends some CPU on tiny bodies. Without `Accept-Encoding` nothing is compressed, so the test suite sees plain JSON. |
| Tomcat `socket.appReadBufSize` / `socket.appWriteBufSize` | 4096 / 4096 (default 8192 / 8192) | Set in `TomcatConfig` with a connector customizer (applied to both connectors) (Spring Boot has no property for them). Tomcat allocates both buffers on the heap for every open connection, idle or not. Measured with 50,000 idle connections after a full GC: 18.0 KB of heap per connection with the defaults (~18 GB at 1M, about 77% of the ~22 GB heap before any request traffic), 9.9 KB with 4 KB buffers (~10 GB at 1M). Tomcat's connector docs say to decrease these "for an extremely high amount of keep alive connections". Requests are under 1 KB, and the processor parses headers in its own buffer, so a 4 KB read buffer is plenty. Larger responses are written in more than one chunk. |
| `spring.datasource.hikari.connection-timeout` | 5000 | A generous wait for a pooled connection under load. |
| Hikari max pool | `DB_POOL`, else min(128, 8 x cores) | The orchestrator's rule for a single-process stack (raised from min(64, 4 x cores)): each connection is round-trip bound at ~600-1600 req/s, and 1M users needs ~42k req/s. Postgres allows 300. |
| `EXTRA_PORT` connector | same settings as the main one | A second listener (3 load generators x 4 IPs x ~64k source ports is ~770k connections per port). |
| `spring.jackson.property-naming-strategy` | SNAKE_CASE | The wire format (`created_at`, `like_count`, ...). |
| `spring.main.banner-mode` | off | Cosmetic. |
| JVM `-XX:+UseG1GC` | G1 | G1 is set explicitly because with 1 usable CPU the JVM's ergonomics would pick SerialGC. G1 is the default for a large heap with high allocation, and ZGC would trade more CPU and memory for latency. |
| JVM `-XX:MaxRAMPercentage=70` | ~22 GiB of 31 GiB (MaxHeapSize 23,571,988,480 on the test box) | The default is 25% (~7.8 GB), which an earlier Spring app filled at ~430k connections. Per-connection Tomcat state lives on the heap: ~9.9 KB with the 4 KB socket buffers above, so ~10 GB at 1M, leaving G1 plenty of headroom for request garbage. |
| JVM `-XX:InitialRAMPercentage=10` | ~3 GB | Starts with a reasonable heap instead of growing from 1/64 of RAM. |

There are no access logs (Tomcat's access log is off by default) and no per-request logging. Errors log at ERROR only.

## Files

- `install.sh`: installs `openjdk-21-jdk-headless` only if JDK 21 is missing. It's idempotent, and Maven comes from the wrapper.
- `build.sh`: `./mvnw -B -q -DskipTests package` builds the fat jar `target/feed.jar`.
- `start.sh`: `exec java -server -XX:+UseG1GC -XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=10 -jar target/feed.jar`. It reads `DATABASE_URL`, `JWT_SECRET`, `HOST`, `PORT` and the optional `EXTRA_PORT` and `DB_POOL` from the environment.
- `src/main/java/com/example/feed/`: controllers (`PostController`, `HealthController`), `TomcatConfig` (socket buffer sizes and the `EXTRA_PORT` connector), JWT auth as a `HandlerMethodArgumentResolver` (it's resolved before the path id, so auth is checked first), `DataSourceConfig` and the error mapping (`GlobalExceptionHandler`).

## Results (the test box, `taskset -c 8-11`, Postgres 18 on the same box)

- Tests: `passed=42 failed=0` on `PORT` (3101) and on `EXTRA_PORT` (3102), each on a fresh copy of `feed_seed`; again after the review and the min(128, 8 x cores) pool change (ports 3921/3922).
- Smoke (both ports): `RESULT users=3000 req/s=127 p95=4.3ms p99=10.0ms err=0.000% pass=true errors=map[]`; after the pool change: `RESULT users=3000 req/s=127 p95=10.4ms p99=29.0ms err=0.000% pass=true errors=map[]` (before the change, same review session: p95=4.4ms p99=10.5ms; at this load the pool is idle, so the difference is most likely run-to-run noise on a shared box (not re-measured)).
- Extra port: both listeners have backlog 8192 (`ss -ltn`), `/feed` is gzipped on both with `Accept-Encoding: gzip`. On 3102: 150 requests on one connection all served (no 100-request cap), 9,000 connections open at once (above Tomcat's 8,192 default cap), half after one request and half never used, all 9,000 served again after 80 s idle.

## Heap-size experiment (2026-10-20)
`MAX_RAM_PCT` (env, read by `start.sh`) overrides `-XX:MaxRAMPercentage=70`; it is unset in the ladder. Why it exists:
past ~330k users Java's live heap fills up with in-flight requests, not connections. Heap histogram after a full GC:
300k users, healthy: ~9 GB live, 66k `Http11Processor`. 360k, collapsing: 22 GB live (`Pause Full 22460M->22460M`),
230k `Http11Processor` + 143k `VirtualThread`, ~80 KB of buffers per processor. Longer GC pauses mean more requests in
flight, which means more live processors, which means longer GC pauses.
