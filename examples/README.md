# Examples: the six servers from the video

Each language's normal framework, set up the normal way for a multicore box, on Postgres. These are the
servers from the video, unchanged: the code, the scripts and a README with every version and non-default
setting. They all pass the test suite. Copy one as a starting point.

Users held for 5 minutes on the 16-core app box, with Postgres on its own 16-core box:

| Folder | Stack | 16 cores |
|---|---|---:|
| [`csharp`](csharp) | ASP.NET Core + Npgsql | 665,000 |
| [`node`](node) | Fastify 5 + node-postgres, `node:cluster` workers | 587,000 |
| [`rust`](rust) | axum + SQLx | 413,000 |
| [`java`](java) | Spring Boot 3.5 (Tomcat, virtual threads) + HikariCP + pgjdbc | 321,000 |
| [`go`](go) | Gin + pgx/v5 `pgxpool` | 248,000 |
| [`python`](python) | FastAPI on Gunicorn/Uvicorn workers + asyncpg | 213,000 |

The follow-ups from the video, each an env switch that is **off by default** in these folders:

| | Before | After | Switch |
|---|---:|---:|---|
| Go | 248,000 | 650,000 | `GZIP_MW=gzhttp TCP_KEEPALIVE=off` (gzip middleware that takes a writer only when it writes; no keep-alive probes) |
| Java | 321,000 | 340,000 | `MAX_RAM_PCT=80` (a bigger heap) |
| C# | 665,000 | 700,000 | `DB_POOL=256` (a bigger connection pool) |
| Node | 587,000 | 639,000 | none: the same code, searched again |

None of them reached a million.
