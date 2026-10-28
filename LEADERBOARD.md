# Leaderboard

Every submission, scored on the same boxes with the same load test. New PRs are benchmarked as they come in and
added here, so this is the only leaderboard.

**The machines.** The app box is a Synteq VPS with 16 dedicated vCPUs (Xeon Platinum 8260) and 32 GB RAM on
Ubuntu 24.04, set up with [`box/setup-app-box.sh`](box/setup-app-box.sh) and nothing else. Postgres 18 runs on its own
16-core box with [`box/postgresql.conf`](box/postgresql.conf). Load comes from two separate 16-core load generators in
the same datacenter, running [`bench/mload`](bench) with `-gzip -think-scale 2.5`.

**How each entry is scored.** Built from source with its own `install.sh` (root) and `build.sh` (user `challenge`),
checked with the 42-check test suite, then started by systemd as `challenge` on a fresh copy of the seed for every
run. Two-minute runs double the users until one fails, then narrow down to within 5%; a 5-minute hold confirms the
number, stepping down 5% until one passes. **The score is the most users held for 5 minutes on 16 cores** (p95 under
500 ms, p99 under 1 s, under 1% errors). A server that holds 1,000,000 is run again on 8 cores, then on 4: `1M on` is
the fewest cores it held a million on.

| # | Submission | PR | Language | Stack | Users held 5 min | 1M on | Scored |
|---:|---|---|---|---|---:|---|---|
| 1 | [`csharp`](examples/csharp) (the video's server) | — | C# | ASP.NET Core + Npgsql | 665,000 | — | 2026-10-19 |
| 2 | [`node`](examples/node) (the video's server) | — | Node | Fastify 5 + node-postgres, `node:cluster` workers | 587,000 | — | 2026-10-19 |
| 3 | [`rust`](examples/rust) (the video's server) | — | Rust | axum + SQLx | 413,000 | — | 2026-10-19 |
| 4 | [`java`](examples/java) (the video's server) | — | Java | Spring Boot 3.5 (Tomcat, virtual threads) + HikariCP + pgjdbc | 321,000 | — | 2026-10-19 |
| 5 | [`go`](examples/go) (the video's server) | — | Go | Gin + pgx/v5 `pgxpool` | 248,000 | — | 2026-10-19 |
| 6 | [`python`](examples/python) (the video's server) | — | Python | FastAPI on Gunicorn/Uvicorn workers + asyncpg | 213,000 | — | 2026-10-19 |

The six example servers were scored in the video with three borrowed load generators instead of the two above; the
test, the boxes and the scoring are otherwise the same.
