use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;

#[derive(Serialize)]
struct ErrorBody {
    error: &'static str,
}

/// Every error the API returns, rendered as `{"error":"<message>"}`.
#[derive(Debug)]
pub enum AppError {
    NotFound,
    InvalidPostId,
    PostNotFound,
    MissingBearerToken,
    InvalidToken,
    InvalidTokenPayload,
    MalformedJson,
    BodyRequired,
    BodyTooLong,
    Internal(sqlx::Error),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            AppError::NotFound => (StatusCode::NOT_FOUND, "not found"),
            AppError::InvalidPostId => (StatusCode::BAD_REQUEST, "invalid post id"),
            AppError::PostNotFound => (StatusCode::NOT_FOUND, "post not found"),
            AppError::MissingBearerToken => (StatusCode::UNAUTHORIZED, "missing bearer token"),
            AppError::InvalidToken => (StatusCode::UNAUTHORIZED, "invalid or expired token"),
            AppError::InvalidTokenPayload => (StatusCode::UNAUTHORIZED, "invalid token payload"),
            AppError::MalformedJson => (StatusCode::BAD_REQUEST, "malformed JSON body"),
            AppError::BodyRequired => (StatusCode::BAD_REQUEST, "body is required"),
            AppError::BodyTooLong => (StatusCode::BAD_REQUEST, "body must be at most 500 characters"),
            AppError::Internal(err) => {
                eprintln!("[error] {err}");
                (StatusCode::INTERNAL_SERVER_ERROR, "internal server error")
            }
        };
        (status, Json(ErrorBody { error: message })).into_response()
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        AppError::Internal(err)
    }
}
