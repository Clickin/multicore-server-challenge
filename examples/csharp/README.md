# std-csharp-pg: ASP.NET Core minimal APIs + Npgsql (Postgres)

The standard C# stack for the challenge feed API (`/root/challenge/SPEC.md`), Postgres variant. It passes `test/test.sh`
(`passed=42 failed=0`) on both ports. It is `std-csharp` (reviewed) with only the data layer swapped from Microsoft.Data.Sqlite
to Npgsql, plus a second listen port. Everything else (Kestrel, gzip, JWT, JSON, error handling) is unchanged.
The Npgsql code (URL conversion, timestamp formatting, FK mapping) comes from the older `servers/csharp`.

## Stack

| Piece | Version |
|---|---|
| Runtime | .NET 10, `Microsoft.NETCore.App` / `Microsoft.AspNetCore.App` 10.0.12, SDK 10.0.401 (`/usr/local/bin/dotnet` -> `/usr/share/dotnet`) |
| Web framework | ASP.NET Core minimal APIs on Kestrel (shared framework, Socket transport) |
| Postgres driver | Npgsql 10.0.3 + Npgsql.DependencyInjection 10.0.3 (`AddNpgsqlDataSource`), ADO.NET, no Dapper/EF. Tested against PostgreSQL 18.6 |
| JWT | Microsoft.AspNetCore.Authentication.JwtBearer 10.0.12 (Microsoft.IdentityModel.JsonWebTokens 8.19.2) + `HMACSHA256` from the BCL for the signature (see below) |
| JSON | System.Text.Json (in the box) |
| Compression | ResponseCompression middleware, gzip provider |

`dotnet publish -c Release`, framework-dependent. No self-contained, Native AOT or ReadyToRun. One GC setting: the heap hard limit (below).

## Process, thread, pool model

- **One process.** Kestrel accepts on `HOST:PORT` and `HOST:EXTRA_PORT`; requests run on the .NET thread pool. `Environment.ProcessorCount`
  reads `sched_getaffinity`, so with `AllowedCPUs`/`taskset` it is the cores we were given. The thread pool minimum,
  Kestrel's Socket IO queues and the Server GC heap count all use it. Logged at startup as `cores=N`.
- **GC:** Server GC (the Web SDK default; DATAS on by default in .NET 9+), with a heap hard limit of 70% of RAM
  (`DOTNET_GCHeapHardLimitPercent=0x46` in `start.sh`, ~21.7 GB on the 31 GB box; logged at startup as `gc_heap_limit_mb`).
  Why: without a limit, Server GC on a plain VM sizes itself against all of RAM and lets the heap grow; in the SQLite runs
  (`std-csharp`, STANDARD.md) RSS reached ~29 GB at 760-800k connections and latency collapsed (p95 18-23 s), while a 20 GB hard limit
  (or `GCConserveMemory=5`) held 850k at ~21 GB. A heap cap is ordinary production config for a dedicated box: it is the .NET
  counterpart of Java's `-XX:MaxRAMPercentage=70` (which the brief prescribes), and .NET already applies 75% automatically when it
  runs under a container memory limit. Override with `DOTNET_GCHeapHardLimitPercent` (hex) in the environment. On **1 core** the runtime falls back to
  Workstation GC (documented .NET behaviour; the startup line shows `server_gc=False`).
- **Idle connections:** same Kestrel as std-csharp: about 10-14 KB RSS per idle keep-alive connection (measured there with 60,000).
- **DB pool:** one `NpgsqlDataSource` (registered with `AddNpgsqlDataSource`, injected into the handlers). Npgsql's own pool,
  `Maximum Pool Size = min(64, 4 x cores)` (single-process stack, so this is the total for the app): 4 at 1 core, 16 at 4, 32 at 8,
  64 at 16. `DB_POOL` overrides it. `Timeout=5`: Npgsql uses it for the connect and for the wait for a free pooled connection when
  all are busy, so a request waits at most ~5 s for a connection. Logged at startup as `db_max_pool_size=N`.
  Npgsql's pool is bounded, so the SQLite version's `SemaphoreSlim` gate is gone.
- **Async DB calls.** Npgsql is truly async: `ExecuteReaderAsync`/`ReadAsync`/`ExecuteNonQueryAsync`/`ExecuteScalarAsync`, so a request
  waiting on Postgres or on the pool holds no thread. Each handler does `dataSource.CreateCommand(sql)`: the command takes a pooled
  connection on execute and returns it when disposed (Npgsql's documented data-source idiom).
- **SQL:** the SPEC's reference queries verbatim (`$1` positional parameters, `::int` on like_count), one statement per request,
  autocommit. Npgsql defaults otherwise (extended protocol, no auto-prepare, no multiplexing).
  The like endpoint maps `PostgresException.SqlState == 23503` (`PostgresErrorCodes.ForeignKeyViolation`) to 404 `post not found`;
  inserted-or-not is the rows-affected count.
- **created_at:** formatted in the app. Npgsql returns `timestamptz` as a `DateTime` with `Kind=Utc`;
  `ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", InvariantCulture)`. .NET's `fff` truncates (does not round) to milliseconds.

## Pool exhaustion (verified)

Pool 16 (`taskset -c 8-11`), a psql session holding `LOCK TABLE likes IN ACCESS EXCLUSIVE MODE` for 12 s, then 200 concurrent
`GET /feed`: 16 requests sat on the lock and returned 200 at 11.5 s; the other 184 waited asynchronously in Npgsql's pool
(`PoolingDataSource.RentAsync`; process threads 23 -> 24 during the burst, so no thread is parked per waiter) and returned
500 `{"error":"internal server error"}` at 5.00-5.05 s ("The connection pool has been exhausted ... Timeout (currently 5 seconds)").
The next request succeeded. Each such 500 logs the exception at Error level (ASP.NET's default `UseExceptionHandler` logging).

## Npgsql performance options left at their defaults

- `Max Auto Prepare`: default 0 (off). The Npgsql docs call automatic preparation "somewhat experimental" and say to test it
  carefully; it is opt-in, not a normal production default. Not enabled.
- `No Reset On Close`: default false. The reset (`DISCARD ALL`) is written into the buffer and sent with the next statement,
  so it costs no extra round trip; disabling it trades away isolation of session state for a small gain. Not enabled.
- Multiplexing: off by default, an opt-in advanced mode. Not enabled.
- Prepared statements in code (`cmd.Prepare()`): not used; the brief asks for driver defaults (extended protocol, unprepared).

## Two ports

`EXTRA_PORT` (optional) adds a second Kestrel endpoint: `ConfigureKestrel` calls `Listen(IPAddress.Parse(HOST), port)` for `PORT` and for
`EXTRA_PORT` (`ListenAnyIP` for `0.0.0.0`, `ListenLocalhost` for `localhost`). Same server, same process, same pipeline; the Kestrel limits
are server-wide, and the Socket transport backlog applies to every endpoint, so both ports get identical settings. `app.Run()` takes no URL.

## JWT


JwtBearer with `JsonWebTokenHandler` is the standard. With the 23-byte (184-bit) challenge secret it rejects every token:
IdentityModel refuses HMAC keys under 256 bits for HS256 (IDX10720). It shows up as `IDX10517 Signature validation failed`.
Proof: the same build passes all 42 tests with a 32-byte secret. IdentityModel has no option to allow short keys, so the
signature check uses the documented `TokenValidationParameters.SignatureValidator` hook:

- parse with `JsonWebToken`; malformed -> 401 `invalid or expired token`
- `alg` must be `HS256` (so `none`/HS512 are rejected)
- `HMACSHA256.HashData(key, header.payload)` and `CryptographicOperations.FixedTimeEquals`

Everything else stays with JwtBearer: `exp` is required and validated (`ClockSkew = 0`), and claims come from the token.
`OnTokenValidated` checks that `sub` is a positive-integer string and `username` is a string (`invalid token payload`).
`OnChallenge` writes the SPEC 401 bodies. `MapInboundClaims = false` keeps `sub`/`username` as they are.

## Non-default settings and why

| Setting | Value | Why |
|---|---|---|
| Socket transport `Backlog` | 65535 (default 512) | 1M clients connecting; the kernel caps it at `net.core.somaxconn` (65535 on the boxes) |
| Kestrel `MaxConcurrentConnections` | `null` (unlimited, set explicitly; it is the default) | no connection cap below 2,000,000 |
| Kestrel `KeepAliveTimeout` | 130 s (set explicitly; it is the default) | clients idle up to ~40 s; the brief needs >= 75 s |
| Kestrel endpoints | `PORT` + optional `EXTRA_PORT` | ~770k connections per server port from the load generators |
| `AddResponseCompression` + `GzipCompressionProvider` | gzip only, default MIME list (includes `application/json`), default level (`CompressionLevel.Fastest`) | clients send `Accept-Encoding: gzip`; plain responses when they don't |
| `PropertyNamingPolicy = SnakeCaseLower` | | wire keys are snake_case |
| `SignatureValidator` | HMACSHA256 | short-key rejection, above |
| `ClockSkew = 0` | default 5 min | the SPEC says expired means expired |
| Npgsql `Maximum Pool Size` | `min(64, 4 x cores)`, env `DB_POOL` overrides | the brief's pool formula (Postgres has 16 cores; total <= 64). Npgsql's default is 100 |
| `DOTNET_GCHeapHardLimitPercent` | `0x46` = 70% of RAM (default: none outside containers) | heap growth at ~800k connections, see GC above |
| Npgsql `Timeout` | 5 s (default 15) | the brief's ~5 s pool acquire timeout |
| `DATABASE_URL` | `postgres://user:pass@host:port/db` converted to a keyword connection string | Npgsql does not parse URLs |
| Logging `Npgsql` | `Warning` | `AddNpgsqlDataSource` hooks Npgsql into ASP.NET logging, and Npgsql logs every executed command at Information; that is per-request logging |
| `UseExceptionHandler` | 500 `{"error":"internal server error"}` | the default is an empty 500 |
| `MapFallback` | 404 `{"error":"not found"}` | the default is an empty 404 |
| `POST /posts` body | `JsonDocument.ParseAsync(Request.Body)` | `malformed JSON body` vs `body is required` (DTO binding can't tell them apart); works with any Content-Type |
| Id parsing | `long.TryParse(NumberStyles.None) && > 0` | digits only: `0`, `-1`, `abc`, `1.5` -> 400 |
| Other Kestrel limits | defaults (`RequestHeadersTimeout` 30 s, data-rate minimums 240 B/s after 5 s grace) | see std-csharp README; they don't affect idle keep-alive clients |
| Logging | `appsettings.json` (`Microsoft.AspNetCore: Warning`, `Npgsql: Warning`), Production | no per-request logs; just startup lines and errors |

STJ's default encoder escapes non-ASCII and `<>&'+`, which the SPEC allows. Responses are `application/json; charset=utf-8`, chunked.
On first start, Data Protection (registered by the auth services but unused) logs a one-time "No XML encryptor configured" warning. It is harmless.

## Scripts

- `install.sh` (root, idempotent): exits if a .NET 10 SDK and the ASP.NET Core 10 runtime are already on PATH. Otherwise it runs
  Microsoft's `dotnet-install.sh --channel 10.0` into `/usr/share/dotnet` and links `/usr/local/bin/dotnet`.
- `build.sh`: `dotnet publish FeedApi.csproj -c Release -o out` (restores NuGet packages; idempotent).
- `start.sh`: `exec dotnet out/FeedApi.dll` with `ASPNETCORE_ENVIRONMENT=Production`. Reads `DATABASE_URL`, `JWT_SECRET`, `HOST`, `PORT`,
  and optional `EXTRA_PORT`, `DB_POOL`.

## Results on the test box (taskset -c 8-11, Postgres 18.6 on the same box)

- `test.sh` on PORT 3107 (fresh DB from `feed_seed`): `passed=42 failed=0`
- `test.sh` on EXTRA_PORT 3108 (another fresh DB): `passed=42 failed=0`
- Smoke (3000 users over both ports, 15 s ramp, 45 s hold, gzip): `RESULT users=3000 req/s=127 p95=2.9ms p99=20.6ms err=0.000% pass=true`,
  server RSS ~200 MB, 26 threads.
- Review re-run with the GC heap limit (`gc_heap_limit_mb=22465` on the test box): `test.sh` `passed=42 failed=0` on 3927 and on 3928 (fresh DB each);
  smoke at `taskset -c 8-15` (pool 32): `RESULT users=3000 req/s=128 p95=2.0ms p99=4.5ms err=0.000% pass=true`, RSS 205 MB.
- 1000 idle keep-alive connections on each port all answered 200 after 85 s idle.

## Pool size change (after review)
Pool = min(128, 8 x cores) (was min(64, 4 x cores)): 8, 16, 32, 64, 128 at 1/2/4/8/16 cores. Each connection serves only ~600-1,600 req/s because every query waits on the network round trip to Postgres on the test box, and 1M users is ~42k req/s. The std-go-pg review measured at 4 cores: pool 16 -> 9.9k req/s (p95 218 ms), pool 32 -> 12.4k (p95 94 ms). Postgres allows 300 connections; most of these sit idle on the network.

## Auto-prepare (after the first Postgres rig check)
`Max Auto Prepare=10; Auto Prepare Min Usages=2`: Npgsql's documented automatic preparation, off by default. The other five drivers prepare statements by default; without it Postgres parses and plans every query. On the target at ~11.4k req/s (300k users, 16 cores) Postgres used 47% CPU behind this app vs 26% behind std-go-pg (pgx, prepares by default).
