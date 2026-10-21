//! Request extractors with the error bodies SPEC.md requires (instead of axum's plain-text rejections).

use axum::{
    body::Bytes,
    extract::{FromRequest, FromRequestParts, Path, Request},
    http::{header::CONTENT_TYPE, request::Parts},
    response::{IntoResponse, Response},
};
use serde_json::Value;

use crate::error::AppError;

/// Mirrors JavaScript's `Number(s)` followed by "positive integer" checks, as the Node reference does.
pub fn parse_positive_int(s: &str) -> Option<i64> {
    let n: f64 = s.trim().parse().ok()?;
    (n.is_finite() && n > 0.0 && n.fract() == 0.0).then_some(n as i64)
}

/// `:id` path parameter; anything that isn't a positive integer is 400 "invalid post id".
pub struct PostId(pub i64);

impl<S: Send + Sync> FromRequestParts<S> for PostId {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &S) -> Result<Self, Self::Rejection> {
        let Path(raw) = Path::<String>::from_request_parts(parts, state)
            .await
            .map_err(|_| AppError::InvalidPostId)?;
        parse_positive_int(&raw).map(PostId).ok_or(AppError::InvalidPostId)
    }
}

/// A JSON request body, parsed like Express's `express.json()`: without an
/// `application/json` content type (or with an empty body) there is no body (`None`);
/// otherwise it must be a JSON object or array.
pub struct JsonBody(pub Option<Value>);

impl<S: Send + Sync> FromRequest<S> for JsonBody {
    type Rejection = Response;

    async fn from_request(req: Request, state: &S) -> Result<Self, Self::Rejection> {
        let is_json = req
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.split(';').next())
            .is_some_and(|mime| mime.trim().eq_ignore_ascii_case("application/json"));
        if !is_json {
            return Ok(JsonBody(None));
        }
        let bytes = Bytes::from_request(req, state).await.map_err(IntoResponse::into_response)?;
        let first = bytes.iter().find(|b| !matches!(b, b' ' | b'\t' | b'\n' | b'\r'));
        match first {
            None => Ok(JsonBody(None)),
            Some(b'{' | b'[') => serde_json::from_slice(&bytes)
                .map(|v| JsonBody(Some(v)))
                .map_err(|_| AppError::MalformedJson.into_response()),
            Some(_) => Err(AppError::MalformedJson.into_response()),
        }
    }
}
