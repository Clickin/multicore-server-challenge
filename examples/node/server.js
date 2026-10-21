'use strict';
// Feed API: Node 22 + Fastify 5 + pg (node-postgres) Pool + @fastify/compress + fast-jwt.
// node:cluster: the primary forks one worker per CPU the process may run on
// (os.availableParallelism() reads sched_getaffinity on Linux) and restarts any worker that dies.
const cluster = require('node:cluster');
const os = require('node:os');

// On one CPU there is nothing to spread over, so run a plain single process (no cluster primary
// handing connections to one worker), the way a 1-core deployment is normally run.
const WORKERS = Number(process.env.WORKERS) || os.availableParallelism();
if (cluster.isPrimary && WORKERS > 1) {
  const n = WORKERS;
  // Linux default is cluster.SCHED_RR: the primary accepts every connection and hands the
  // socket to the workers round-robin. Left at the default on purpose.
  console.log(`std-node-pg primary ${process.pid}: ${n} workers, schedulingPolicy=${cluster.schedulingPolicy === cluster.SCHED_RR ? 'SCHED_RR' : 'SCHED_NONE'}`);
  for (let i = 0; i < n; i++) cluster.fork();
  cluster.on('exit', (w, code, signal) => {
    console.error(`worker ${w.process.pid} exited (code=${code} signal=${signal}), restarting`);
    cluster.fork();
  });
  for (const sig of ['SIGTERM', 'SIGINT']) {
    process.on(sig, () => {
      cluster.removeAllListeners('exit');
      for (const id in cluster.workers) cluster.workers[id].process.kill('SIGTERM');
      process.exit(0);
    });
  }
  return;
}

const http = require('node:http');
const { Pool } = require('pg');
const { createVerifier } = require('fast-jwt');
const fastifyFactory = require('fastify');

const DATABASE_URL = process.env.DATABASE_URL;
const JWT_SECRET = process.env.JWT_SECRET;
const HOST = process.env.HOST || '127.0.0.1';
const PORT = Number(process.env.PORT || 3000);
const EXTRA_PORT = process.env.EXTRA_PORT ? Number(process.env.EXTRA_PORT) : 0;
if (!JWT_SECRET) { console.error('JWT_SECRET is required'); process.exit(1); }
if (!DATABASE_URL) { console.error('DATABASE_URL is required'); process.exit(1); }

// One pg Pool per worker process. The whole app stays at <= 128 Postgres connections:
// cluster: max(2, floor(128 / workers)) per worker; 1 core = single process = min(128, 8 x cores) = 8.
// DB_POOL overrides.
const POOL_MAX = Number(process.env.DB_POOL) ||
  (WORKERS > 1 ? Math.max(2, Math.floor(128 / WORKERS)) : Math.min(128, 8 * WORKERS));
const pool = new Pool({
  connectionString: DATABASE_URL,
  max: POOL_MAX,
  connectionTimeoutMillis: 5000, // acquire timeout: a request waits at most 5 s for a free connection
  // pg's default closes a connection after 10 s idle; the pool hands out the most recently used
  // client first, so under bursty load the rest idle out and reconnect (measured ~160 reconnects/min
  // at 4 cores). 5 min idle, like Npgsql's ConnectionIdleLifetime (pgx 30 min, sqlx 10 min, Hikari never).
  idleTimeoutMillis: 300_000,
});
// node-postgres docs: an idle client that errors (server restart, network) emits 'error' on the pool;
// without a listener that would crash the worker.
pool.on('error', (e) => console.error('pg idle client error:', e.message));

const POST_SELECT = `SELECT p.id, p.body, p.created_at, u.username,
       (SELECT count(*) FROM likes l WHERE l.post_id = p.id)::int AS like_count
  FROM posts p
  JOIN users u ON u.id = p.user_id`;
// Named queries: node-postgres prepares each once per connection and reuses it (extended protocol).
const qHealth = { name: 'health', text: 'SELECT 1' };
const qFeed = { name: 'feed', text: POST_SELECT + ' ORDER BY p.created_at DESC, p.id DESC LIMIT $1' };
const qPost = { name: 'post', text: POST_SELECT + ' WHERE p.id = $1' };
const qInsertPost = { name: 'insert_post', text: 'INSERT INTO posts (user_id, body) VALUES ($1, $2) RETURNING id, created_at' };
const qInsertLike = { name: 'insert_like', text: 'INSERT INTO likes (user_id, post_id) VALUES ($1, $2) ON CONFLICT (user_id, post_id) DO NOTHING' };
const query = (q, values) => pool.query({ name: q.name, text: q.text, values });

// BIGINT ids come back as strings (pg's default for int8); like_count is ::int.
// TIMESTAMPTZ comes back as a JS Date; toISOString() is UTC with ms truncated and a Z suffix.
const toPost = (r) => ({ id: Number(r.id), body: r.body, created_at: r.created_at.toISOString(), author: r.username, like_count: r.like_count });

// HS256 only; fast-jwt verifies exp by default (no clock tolerance).
const verify = createVerifier({ key: JWT_SECRET, algorithms: ['HS256'] });

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function parseId(raw) {
  if (!/^[0-9]+$/.test(raw)) throw new HttpError(400, 'invalid post id');
  const id = Number(raw);
  if (!(id > 0) || !Number.isSafeInteger(id)) throw new HttpError(400, 'invalid post id');
  return id;
}

// onRequest hook on the authenticated routes: runs before body parsing and before the id check.
async function authenticate(req) {
  const h = req.headers.authorization;
  if (typeof h !== 'string' || !h.startsWith('Bearer ')) throw new HttpError(401, 'missing bearer token');
  let payload;
  try {
    payload = verify(h.slice(7));
  } catch {
    throw new HttpError(401, 'invalid or expired token');
  }
  const sub = payload && payload.sub;
  if (typeof sub !== 'string' || !/^[0-9]+$/.test(sub) || !(Number(sub) > 0) || typeof payload.username !== 'string') {
    throw new HttpError(401, 'invalid token payload');
  }
  req.user = { id: Number(sub), username: payload.username };
}

const STARTED = Date.now();

// Response schemas: Fastify's documented way to serialize (fast-json-stringify, keys in schema order).
const postSchema = {
  type: 'object',
  properties: {
    id: { type: 'integer' }, body: { type: 'string' }, created_at: { type: 'string' },
    author: { type: 'string' }, like_count: { type: 'integer' },
  },
};
const errorSchema = { type: 'object', properties: { error: { type: 'string' } } };

// Two ports (PORT and optional EXTRA_PORT) on the same Fastify app: Fastify's documented
// `serverFactory` hands us its request handler and its validated options; we build the main
// http.Server with them and, after listen, a second one for EXTRA_PORT with the same handler.
// With serverFactory Fastify skips its own server setup (lib/server.js getServerInstance), so
// makeServer repeats exactly what that function does for HTTP/1: keepAliveTimeout, requestTimeout,
// setTimeout(connectionTimeout), maxRequestsPerSocket if > 0. Both servers get the same values.
const KEEP_ALIVE_MS = 120_000; // clients idle up to ~40 s between requests; Fastify default is 72 s
let fastifyHandler, fastifyOpts;
function makeServer(handler, opts) {
  const server = http.createServer(opts.http || {}, handler);
  server.keepAliveTimeout = opts.keepAliveTimeout;
  server.requestTimeout = opts.requestTimeout; // Fastify default 0 (Node keeps headersTimeout at 60 s)
  server.setTimeout(opts.connectionTimeout);   // 0: no socket inactivity timeout
  if (opts.maxRequestsPerSocket > 0) server.maxRequestsPerSocket = opts.maxRequestsPerSocket;
  server.maxConnections = Infinity; // Node's default is already unlimited; explicit so it is visible
  return server;
}

const app = fastifyFactory({
  logger: false,
  serverFactory: (handler, opts) => { fastifyHandler = handler; fastifyOpts = opts; return makeServer(handler, opts); },
  keepAliveTimeout: KEEP_ALIVE_MS,
  connectionTimeout: 0,      // no socket inactivity timeout (keepAliveTimeout handles idle sockets)
  // requestTimeout: Fastify default (0); Node's headersTimeout default (60 s) still guards slow headers.
  forceCloseConnections: false,
});

app.register(require('@fastify/compress'), { global: true, encodings: ['gzip'] });

app.setNotFoundHandler((req, reply) => { reply.code(404).send({ error: 'not found' }); });

app.setErrorHandler((err, req, reply) => {
  if (err instanceof HttpError) return reply.code(err.status).send({ error: err.message });
  if (err.code === 'FST_ERR_CTP_INVALID_JSON_BODY' || err.code === 'FST_ERR_CTP_EMPTY_JSON_BODY' ||
      (err instanceof SyntaxError && err.statusCode === 400)) {
    return reply.code(400).send({ error: 'malformed JSON body' });
  }
  if (err.statusCode >= 400 && err.statusCode < 500) {
    return reply.code(err.statusCode).send({ error: err.message });
  }
  console.error(err);
  reply.code(500).send({ error: 'internal server error' });
});

app.register(async (r) => {
  r.get('/health', {
    schema: { response: { 200: { type: 'object', properties: { status: { type: 'string' }, db: { type: 'string' }, uptime_s: { type: 'integer' } } } } },
  }, async (req, reply) => {
    try {
      await query(qHealth);
    } catch (e) {
      return reply.code(503).send({ status: 'degraded', db: 'unreachable', error: String(e && e.message || e) });
    }
    return { status: 'ok', db: 'ok', uptime_s: Math.floor((Date.now() - STARTED) / 1000) };
  });

  r.get('/feed', {
    schema: { response: { 200: { type: 'object', properties: { posts: { type: 'array', items: postSchema } } } } },
  }, async () => ({ posts: (await query(qFeed, [20])).rows.map(toPost) }));

  r.get('/posts/:id', {
    schema: { response: { 200: { type: 'object', properties: { post: postSchema } }, '4xx': errorSchema } },
  }, async (req) => {
    const row = (await query(qPost, [parseId(req.params.id)])).rows[0];
    if (!row) throw new HttpError(404, 'post not found');
    return { post: toPost(row) };
  });

  r.post('/posts', {
    onRequest: authenticate,
    schema: { response: { 201: { type: 'object', properties: { post: postSchema } }, '4xx': errorSchema } },
  }, async (req, reply) => {
    const b = req.body;
    let text = b !== null && typeof b === 'object' ? b.body : undefined;
    if (typeof text !== 'string') throw new HttpError(400, 'body is required');
    text = text.trim();
    if (text.length === 0) throw new HttpError(400, 'body is required');
    // "characters" = code points, matching Postgres char_length() in the CHECK constraint
    if (text.length > 500 && [...text].length > 500) throw new HttpError(400, 'body must be at most 500 characters');
    const row = (await query(qInsertPost, [req.user.id, text])).rows[0];
    reply.code(201);
    return { post: { id: Number(row.id), body: text, created_at: row.created_at.toISOString(), author: req.user.username, like_count: 0 } };
  });

  r.post('/posts/:id/like', {
    onRequest: authenticate,
    schema: {
      response: {
        '2xx': { type: 'object', properties: { liked: { type: 'boolean' }, already_liked: { type: 'boolean' }, post_id: { type: 'integer' } } },
        '4xx': errorSchema,
      },
    },
  }, async (req, reply) => {
    const id = parseId(req.params.id);
    let changes;
    try {
      changes = (await query(qInsertLike, [req.user.id, id])).rowCount;
    } catch (e) {
      if (e && e.code === '23503') throw new HttpError(404, 'post not found');
      throw e;
    }
    reply.code(changes === 1 ? 201 : 200);
    return { liked: true, already_liked: changes !== 1, post_id: id };
  });
});

const BACKLOG = 65535;
app.listen({ host: HOST, port: PORT, backlog: BACKLOG }).then(() => {
  if (!EXTRA_PORT) return;
  const extra = makeServer(fastifyHandler, fastifyOpts);
  // Same malformed-request handling as the main server (Fastify attaches its clientErrorHandler there).
  extra.on('clientError', fastifyOpts.clientErrorHandler.bind(app));
  return new Promise((resolve, reject) => {
    extra.once('error', reject);
    extra.listen({ host: HOST, port: EXTRA_PORT, backlog: BACKLOG }, resolve);
  });
}).then(() => {
  if (!cluster.worker || cluster.worker.id === 1) {
    console.log(`std-node-pg worker ${process.pid} listening on ${HOST}:${PORT}${EXTRA_PORT ? ` and ${HOST}:${EXTRA_PORT}` : ''}, pg pool max ${POOL_MAX}`);
  }
}).catch((e) => { console.error(e); process.exit(1); });
