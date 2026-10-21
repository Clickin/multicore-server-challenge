use axum::{
    Json,
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

use crate::SharedState;

// Response structs (not `json!`) so the JSON key order is the field order.
#[derive(Serialize)]
struct Healthy {
    status: &'static str,
    db: &'static str,
    uptime_s: u64,
}

#[derive(Serialize)]
struct Degraded {
    status: &'static str,
    db: &'static str,
    error: String,
}

/// Liveness + a cheap DB round-trip.
pub async fn health(State(state): State<SharedState>) -> Response {
    match sqlx::query("SELECT 1").execute(&state.pool).await {
        Ok(_) => Json(Healthy {
            status: "ok",
            db: "ok",
            uptime_s: state.started.elapsed().as_secs_f64().round() as u64,
        })
        .into_response(),
        Err(err) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Degraded { status: "degraded", db: "unreachable", error: err.to_string() }),
        )
            .into_response(),
    }
}
