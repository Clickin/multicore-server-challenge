use axum::{extract::FromRequestParts, http::{header::AUTHORIZATION, request::Parts}};
use serde_json::Value;

use crate::{SharedState, error::AppError, extract::parse_positive_int};

/// The authenticated user, from `Authorization: Bearer <HS256 JWT>`.
pub struct AuthUser {
    pub id: i64,
    pub username: String,
}

impl FromRequestParts<SharedState> for AuthUser {
    type Rejection = AppError;

    async fn from_request_parts(parts: &mut Parts, state: &SharedState) -> Result<Self, Self::Rejection> {
        let token = parts
            .headers
            .get(AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .ok_or(AppError::MissingBearerToken)?
            .trim();

        let claims = jsonwebtoken::decode::<Value>(token, &state.jwt_key, &state.jwt_validation)
            .map_err(|_| AppError::InvalidToken)?
            .claims;

        let id = match claims.get("sub") {
            Some(Value::String(s)) => parse_positive_int(s),
            Some(Value::Number(n)) => n.as_f64().and_then(|f| parse_positive_int(&f.to_string())),
            _ => None,
        };
        match (id, claims.get("username")) {
            (Some(id), Some(Value::String(username))) => Ok(AuthUser { id, username: username.clone() }),
            _ => Err(AppError::InvalidTokenPayload),
        }
    }
}
