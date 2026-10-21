//! Feed API (challenge SPEC.md): axum 0.8 on a multi-thread tokio runtime, sqlx Postgres pool (PgPool),
//! HS256 JWT auth (jsonwebtoken), gzip via tower-http's CompressionLayer.

mod auth;
mod error;
mod extract;
mod health;
mod posts;

use std::{env, net::SocketAddr, sync::Arc, time::{Duration, Instant}};

use axum::{Router, routing::{get, post}, serve::ListenerExt};
use jsonwebtoken::{Algorithm, DecodingKey, Validation};
use sqlx::{PgPool, postgres::PgPoolOptions};
use tokio::net::{TcpListener, TcpSocket};
use tower_http::compression::CompressionLayer;

use crate::error::AppError;

pub struct AppState {
    pub pool: PgPool,
    pub jwt_key: DecodingKey,
    pub jwt_validation: Validation,
    pub started: Instant,
}

pub type SharedState = Arc<AppState>;

/// Listen backlog (the kernel caps it at net.core.somaxconn).
const LISTEN_BACKLOG: u32 = 65535;

fn env_or(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}

fn main() {
    // Cores the process may run on: available_parallelism() uses sched_getaffinity (and cgroup
    // CPU quotas) on Linux, so systemd CPUAffinity/AllowedCPUs and `taskset` are respected.
    let cores = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    // Same value tokio would pick by default; set explicitly so it is visible and logged.
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(cores)
        .enable_all()
        .build()
        .expect("could not build the tokio runtime")
        .block_on(run(cores));
}

async fn run(cores: usize) {
    let started = Instant::now();
    let database_url = env::var("DATABASE_URL").expect("DATABASE_URL must be set");
    let jwt_secret = env::var("JWT_SECRET").expect("JWT_SECRET must be set");
    let host = env_or("HOST", "127.0.0.1");
    let port: u16 = env_or("PORT", "3000").parse().expect("PORT must be an integer");
    let extra_port: Option<u16> = env::var("EXTRA_PORT")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|s| s.parse().expect("EXTRA_PORT must be an integer"));
    // Single process: pool = min(128, 8 x cores) (8/16/32/64/128 at 1/2/4/8/16 cores), override with DB_POOL.
    let pool_max: u32 = match env::var("DB_POOL") {
        Ok(v) if !v.is_empty() => v.parse().expect("DB_POOL must be an integer"),
        _ => (8 * cores).min(128) as u32,
    };

    // sqlx defaults otherwise (min 0 connections, idle timeout 10 min, max lifetime 30 min,
    // statement cache 100 per connection); acquire timeout lowered from 30 s to 5 s (brief).
    let pool = PgPoolOptions::new()
        .max_connections(pool_max)
        .acquire_timeout(Duration::from_secs(5))
        .connect(&database_url)
        .await
        .expect("could not connect to Postgres");

    let state = Arc::new(AppState {
        pool,
        jwt_key: DecodingKey::from_secret(jwt_secret.as_bytes()),
        jwt_validation: Validation::new(Algorithm::HS256),
        started,
    });

    let app = Router::new()
        .route("/health", get(health::health))
        .route("/feed", get(posts::feed))
        .route("/posts", post(posts::create_post))
        .route("/posts/{id}", get(posts::get_post))
        .route("/posts/{id}/like", post(posts::like_post))
        .fallback(|| async { AppError::NotFound })
        .layer(CompressionLayer::new()) // gzip when the client sends Accept-Encoding: gzip
        .with_state(state);

    // PORT, plus EXTRA_PORT if set: the same app (same router, state and pool) served on two
    // listeners, each with its own axum::serve task on the same tokio runtime.
    let mut ports = vec![port];
    ports.extend(extra_port);
    let mut servers = tokio::task::JoinSet::new();
    for p in ports {
        let listener = bind(&host, p).await;
        println!(
            "[server] listening on http://{} (pid {}, {cores} tokio workers, sqlx pool max {pool_max})",
            listener.local_addr().expect("local_addr"),
            std::process::id()
        );
        // axum::serve: one tokio task per connection, hyper HTTP/1.1 (+h2) with keep-alive and no idle
        // timeout (no hyper timer is installed, so header_read_timeout is inactive).
        let listener = listener.tap_io(|tcp| {
            let _ = tcp.set_nodelay(true);
        });
        // `into_make_service()` (documented axum API) finalizes the router once and hands every
        // connection an Arc clone. Passing the bare `Router` makes axum call `Router::with_state(())`
        // per accepted connection, which rebuilds the whole route table (~5.6 KB of heap per
        // connection, held for the connection's lifetime).
        let service = app.clone().into_make_service();
        servers.spawn(async move {
            axum::serve(listener, service)
                .with_graceful_shutdown(shutdown_signal())
                .await
                .expect("server error");
        });
    }
    while let Some(res) = servers.join_next().await {
        res.expect("server task panicked");
    }
}

/// tokio's TcpListener::bind() hard-codes a backlog of 1024; build the socket ourselves to raise it.
async fn bind(host: &str, port: u16) -> TcpListener {
    let addr: SocketAddr = tokio::net::lookup_host((host, port))
        .await
        .expect("could not resolve HOST")
        .next()
        .expect("HOST resolved to no address");
    let socket = if addr.is_ipv4() { TcpSocket::new_v4() } else { TcpSocket::new_v6() }.expect("socket");
    socket.set_reuseaddr(true).expect("SO_REUSEADDR");
    socket.bind(addr).expect("could not bind");
    socket.listen(LISTEN_BACKLOG).expect("listen")
}

async fn shutdown_signal() {
    let ctrl_c = async { tokio::signal::ctrl_c().await.expect("failed to install Ctrl+C handler") };
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };
    tokio::select! { _ = ctrl_c => {}, _ = terminate => {} }
    println!("[server] shutting down");
}
