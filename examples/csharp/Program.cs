// Feed API (challenge SPEC.md), Postgres variant: ASP.NET Core minimal APIs on Kestrel, Npgsql (ADO.NET,
// async, pooled NpgsqlDataSource), System.Text.Json, JwtBearer, ResponseCompression (gzip).
using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.ResponseCompression;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Npgsql;

var builder = WebApplication.CreateBuilder(args);

string Env(string name, string? fallback = null) =>
    builder.Configuration[name] is { Length: > 0 } value
        ? value
        : fallback ?? throw new InvalidOperationException($"missing required environment variable {name}");

var databaseUrl = Env("DATABASE_URL");
var jwtSecret = Env("JWT_SECRET");
var host = Env("HOST", "127.0.0.1");
var port = int.Parse(Env("PORT", "3000"), CultureInfo.InvariantCulture);
int? extraPort = builder.Configuration["EXTRA_PORT"] is { Length: > 0 } ep ? int.Parse(ep, CultureInfo.InvariantCulture) : null;

// ---- Kestrel: unlimited connections (MaxConcurrentConnections = null is the default), 130 s keep-alive
// (the default, >= 75 s), and a big accept backlog for 1M clients connecting (the default is 512).
builder.WebHost.UseSockets(o => o.Backlog = 65535);
// Two endpoints (PORT and, if set, EXTRA_PORT) on the same Kestrel server; the limits are server-wide, so both
// get identical settings.
builder.WebHost.ConfigureKestrel(o =>
{
    o.Limits.MaxConcurrentConnections = null;
    o.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(130);
    void Bind(int p)
    {
        if (host is "0.0.0.0" or "*" or "") o.ListenAnyIP(p);
        else if (host == "localhost") o.ListenLocalhost(p);
        else o.Listen(IPAddress.Parse(host), p);
    }
    Bind(port);
    if (extraPort is int x) Bind(x);
});

// ---- Postgres: Environment.ProcessorCount honours sched_getaffinity, so it is the cores systemd gives us.
// One process, so the whole app's pool is min(128, 8 x cores); DB_POOL overrides it (8 per core: each connection serves
// only ~600-1600 req/s because of the round trip to Postgres on another box).
var cores = Environment.ProcessorCount;
var poolSize = int.TryParse(Environment.GetEnvironmentVariable("DB_POOL"), out var dbPool) && dbPool > 0 ? dbPool : Math.Min(128, 8 * cores);
builder.Services.AddNpgsqlDataSource(Db.ToConnectionString(databaseUrl, poolSize));

// ---- JSON: snake_case keys (created_at, like_count, already_liked, post_id, uptime_s) ----
builder.Services.ConfigureHttpJsonOptions(o =>
    o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower);

// ---- gzip for application/json (in the default MIME list) ----
builder.Services.AddResponseCompression(o => o.Providers.Add<GzipCompressionProvider>());

// ---- Auth: HS256 bearer tokens; the secret's UTF-8 bytes are the key ----
var jwtKey = Encoding.UTF8.GetBytes(jwtSecret);
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(o =>
    {
        o.MapInboundClaims = false; // keep "sub" and "username" as-is
        o.TokenValidationParameters = new TokenValidationParameters
        {
            // IdentityModel refuses HMAC keys under 256 bits (the 23-byte challenge secret is 184 bits), so the
            // signature check is the documented SignatureValidator hook: HS256 only, HMACSHA256 from the BCL,
            // constant-time compare. Everything else (exp/nbf, ClockSkew, claims) stays with JwtBearer.
            SignatureValidator = (token, _) => Auth.VerifyHs256(token, jwtKey),
            ValidateIssuer = false,
            ValidateAudience = false,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.Zero,
        };
        o.Events = new JwtBearerEvents
        {
            OnTokenValidated = ctx =>
            {
                if (Auth.GetUser(ctx.Principal) is null) ctx.Fail(Auth.InvalidPayload);
                return Task.CompletedTask;
            },
            OnChallenge = async ctx =>
            {
                ctx.HandleResponse(); // SPEC bodies instead of the default empty 401
                var message = ctx.AuthenticateFailure switch
                {
                    null => "missing bearer token",
                    { Message: Auth.InvalidPayload } => Auth.InvalidPayload,
                    _ => "invalid or expired token",
                };
                await Results.Json(new ErrorBody(message), statusCode: 401).ExecuteAsync(ctx.HttpContext);
            },
        };
    });
builder.Services.AddAuthorization();

var app = builder.Build();

app.UseResponseCompression();
app.UseExceptionHandler(errorApp => errorApp.Run(ctx =>
    Results.Json(new ErrorBody("internal server error"), statusCode: 500).ExecuteAsync(ctx)));

var started = Stopwatch.StartNew();

// ---- routes ----
app.MapGet("/health", async (NpgsqlDataSource db) =>
{
    try
    {
        await using var cmd = db.CreateCommand("SELECT 1");
        await cmd.ExecuteScalarAsync();
        return Results.Json(new Health("ok", "ok", (long)started.Elapsed.TotalSeconds));
    }
    catch (Exception ex)
    {
        return Results.Json(new Degraded("degraded", "unreachable", ex.Message), statusCode: 503);
    }
});

app.MapGet("/feed", async (NpgsqlDataSource db) =>
{
    var posts = new List<Post>(Db.FeedSize);
    await using var cmd = db.CreateCommand(Db.PostSelect + " ORDER BY p.created_at DESC, p.id DESC LIMIT $1");
    cmd.Parameters.AddWithValue(Db.FeedSize);
    await using var reader = await cmd.ExecuteReaderAsync();
    while (await reader.ReadAsync()) posts.Add(Db.ReadPost(reader));
    return Results.Json(new FeedResponse(posts));
});

app.MapGet("/posts/{id}", async (string id, NpgsqlDataSource db) =>
{
    if (!Db.TryParseId(id, out var postId)) return Errors.InvalidPostId;
    await using var cmd = db.CreateCommand(Db.PostSelect + " WHERE p.id = $1");
    cmd.Parameters.AddWithValue(postId);
    await using var reader = await cmd.ExecuteReaderAsync();
    if (!await reader.ReadAsync()) return Errors.PostNotFound;
    return Results.Json(new PostResponse(Db.ReadPost(reader)));
});

app.MapPost("/posts", async (HttpRequest request, ClaimsPrincipal principal, NpgsqlDataSource db) =>
{
    var user = Auth.GetUser(principal)!;
    string body;
    try
    {
        using var json = await JsonDocument.ParseAsync(request.Body);
        body = json.RootElement.ValueKind == JsonValueKind.Object
               && json.RootElement.TryGetProperty("body", out var b) && b.ValueKind == JsonValueKind.String
            ? b.GetString()!.Trim()
            : "";
    }
    catch (JsonException)
    {
        return Results.Json(new ErrorBody("malformed JSON body"), statusCode: 400);
    }
    if (body.Length == 0) return Results.Json(new ErrorBody("body is required"), statusCode: 400);
    if (body.Length > 500) return Results.Json(new ErrorBody("body must be at most 500 characters"), statusCode: 400);

    await using var cmd = db.CreateCommand("INSERT INTO posts (user_id, body) VALUES ($1, $2) RETURNING id, created_at");
    cmd.Parameters.AddWithValue(user.Id);
    cmd.Parameters.AddWithValue(body);
    await using var reader = await cmd.ExecuteReaderAsync();
    await reader.ReadAsync();
    var post = new Post(reader.GetInt64(0), body, Db.FormatTimestamp(reader.GetDateTime(1)), user.Username, 0);
    return Results.Json(new PostResponse(post), statusCode: 201);
}).RequireAuthorization();

app.MapPost("/posts/{id}/like", async (string id, ClaimsPrincipal principal, NpgsqlDataSource db) =>
{
    var user = Auth.GetUser(principal)!;
    if (!Db.TryParseId(id, out var postId)) return Errors.InvalidPostId;
    await using var cmd = db.CreateCommand(
        "INSERT INTO likes (user_id, post_id) VALUES ($1, $2) ON CONFLICT (user_id, post_id) DO NOTHING");
    cmd.Parameters.AddWithValue(user.Id);
    cmd.Parameters.AddWithValue(postId);
    int inserted;
    try
    {
        inserted = await cmd.ExecuteNonQueryAsync();
    }
    catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.ForeignKeyViolation) // 23503
    {
        return Errors.PostNotFound;
    }
    return Results.Json(new LikeResponse(true, inserted == 0, postId), statusCode: inserted > 0 ? 201 : 200);
}).RequireAuthorization();

// Unknown paths (any method) -> 404 {"error":"not found"}.
app.MapFallback("{*path}", () => Results.Json(new ErrorBody("not found"), statusCode: 404));

await using (var versionCmd = app.Services.GetRequiredService<NpgsqlDataSource>().CreateCommand("SHOW server_version"))
    app.Logger.LogInformation("cores={Cores} db_max_pool_size={Max} server_gc={ServerGc} gc_heap_limit_mb={HeapLimit} ports={Port},{ExtraPort} postgres={Version}",
        cores, poolSize, System.Runtime.GCSettings.IsServerGC, GC.GetGCMemoryInfo().TotalAvailableMemoryBytes >> 20,
        port, extraPort?.ToString() ?? "-", await versionCmd.ExecuteScalarAsync());
app.Run();

// ---- types & helpers ----
record Post(long Id, string Body, string CreatedAt, string Author, int LikeCount);
record FeedResponse(List<Post> Posts);
record PostResponse(Post Post);
record LikeResponse(bool Liked, bool AlreadyLiked, long PostId);
record Health(string Status, string Db, long UptimeS);
record Degraded(string Status, string Db, string Error);
record ErrorBody(string Error);
record AuthUser(long Id, string Username);

static class Errors
{
    public static readonly IResult InvalidPostId = Results.Json(new ErrorBody("invalid post id"), statusCode: 400);
    public static readonly IResult PostNotFound = Results.Json(new ErrorBody("post not found"), statusCode: 404);
}

static class Auth
{
    public const string InvalidPayload = "invalid token payload";

    // sub must be a positive integer string and username a string.
    public static AuthUser? GetUser(ClaimsPrincipal? principal)
    {
        var sub = principal?.FindFirst("sub");
        var username = principal?.FindFirst("username");
        if (sub is null || sub.ValueType != ClaimValueTypes.String) return null;
        if (!long.TryParse(sub.Value, NumberStyles.None, CultureInfo.InvariantCulture, out var id) || id <= 0) return null;
        if (username is null || username.ValueType != ClaimValueTypes.String) return null;
        return new AuthUser(id, username.Value);
    }

    public static SecurityToken VerifyHs256(string token, byte[] key)
    {
        var jwt = new JsonWebToken(token); // throws on a malformed token -> 401 invalid or expired token
        if (jwt.Alg != SecurityAlgorithms.HmacSha256)
            throw new SecurityTokenInvalidAlgorithmException($"alg {jwt.Alg} not allowed");
        var lastDot = token.LastIndexOf('.');
        Span<byte> expected = stackalloc byte[HMACSHA256.HashSizeInBytes];
        HMACSHA256.HashData(key, Encoding.ASCII.GetBytes(token, 0, lastDot), expected);
        byte[] actual;
        try { actual = Base64UrlEncoder.DecodeBytes(token[(lastDot + 1)..]); }
        catch (FormatException) { throw new SecurityTokenInvalidSignatureException("bad signature encoding"); }
        if (!CryptographicOperations.FixedTimeEquals(expected, actual))
            throw new SecurityTokenInvalidSignatureException("bad signature");
        return jwt;
    }
}

// Npgsql's NpgsqlDataSource owns the pool (Max Pool Size = the formula above; Timeout=5 s also bounds the wait for
// a free pooled connection). Each command takes a connection from the pool and returns it when disposed.
static class Db
{
    public const int FeedSize = 20;

    public const string PostSelect = """
        SELECT p.id, p.body, p.created_at, u.username,
               (SELECT count(*) FROM likes l WHERE l.post_id = p.id)::int AS like_count
          FROM posts p
          JOIN users u ON u.id = p.user_id
        """;

    public static Post ReadPost(NpgsqlDataReader r) =>
        new(r.GetInt64(0), r.GetString(1), FormatTimestamp(r.GetDateTime(2)), r.GetString(3), r.GetInt32(4));

    // timestamptz arrives as a UTC DateTime (Kind=Utc, microsecond precision); "fff" truncates to milliseconds.
    public static string FormatTimestamp(DateTime utc) =>
        utc.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);

    public static bool TryParseId(string raw, out long id) =>
        long.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out id) && id > 0;

    // DATABASE_URL is postgres://user:pass@host:port/db; Npgsql wants a keyword connection string.
    public static string ToConnectionString(string url, int maxPoolSize)
    {
        var uri = new Uri(url);
        var userInfo = uri.UserInfo.Split(':', 2);
        return new NpgsqlConnectionStringBuilder
        {
            Host = uri.Host,
            Port = uri.IsDefaultPort || uri.Port <= 0 ? 5432 : uri.Port,
            Username = Uri.UnescapeDataString(userInfo[0]),
            Password = userInfo.Length > 1 ? Uri.UnescapeDataString(userInfo[1]) : null,
            Database = Uri.UnescapeDataString(uri.AbsolutePath.TrimStart('/')),
            MaxPoolSize = maxPoolSize,
            Timeout = 5, // connect timeout, and the wait for a pooled connection when all are in use
            // Npgsql's documented automatic preparation (off by default). Every other stack's driver prepares statements
            // by default (pgx, sqlx, pgjdbc after 5 uses, asyncpg, node-postgres named queries); without it Postgres
            // parses and plans every query: measured 47% vs 26% Postgres CPU at ~11.4k req/s (C# vs Go, same load).
            MaxAutoPrepare = 10,
            AutoPrepareMinUsages = 2,
            // No session reset when a connection goes back to the pool (Npgsql's perf docs; pgx, sqlx, Hikari and
            // node-postgres don't reset either). Every request here returns its connection, so otherwise Postgres runs
            // a reset statement per request. Safe: the app never changes session state.
            NoResetOnClose = true,
        }.ConnectionString;
    }
}
