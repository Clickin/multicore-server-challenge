use axum::{Json, extract::State, http::StatusCode, response::IntoResponse};
use chrono::{DateTime, Utc};
use serde::Serialize;
use serde_json::Value;

use crate::{
    SharedState,
    auth::AuthUser,
    error::AppError,
    extract::{JsonBody, PostId},
};

const FEED_SIZE: i64 = 20;
const MAX_POST_BODY_LENGTH: usize = 500;

/// Shared SELECT (a macro so the full queries stay `&'static str`, as sqlx requires).
macro_rules! post_select {
    () => {
        "
  SELECT p.id, p.body, p.created_at, u.username,
         (SELECT count(*) FROM likes l WHERE l.post_id = p.id)::int AS like_count
    FROM posts p
    JOIN users u ON u.id = p.user_id"
    };
}
const FEED_SQL: &str = concat!(post_select!(), "\n     ORDER BY p.created_at DESC, p.id DESC\n     LIMIT $1");
const POST_BY_ID_SQL: &str = concat!(post_select!(), " WHERE p.id = $1");

#[derive(sqlx::FromRow)]
struct PostRow {
    id: i64,
    body: String,
    created_at: DateTime<Utc>,
    username: String,
    like_count: i32,
}

/// Wire format; field order is the JSON key order.
#[derive(Serialize)]
struct Post {
    id: i64,
    body: String,
    created_at: String,
    author: String,
    like_count: i32,
}

#[derive(Serialize)]
struct FeedResponse {
    posts: Vec<Post>,
}

#[derive(Serialize)]
struct PostResponse {
    post: Post,
}

#[derive(Serialize)]
struct LikeResponse {
    liked: bool,
    already_liked: bool,
    post_id: i64,
}

/// `2025-12-31T23:59:19.409Z`: UTC, milliseconds truncated (like JavaScript's `Date`).
fn format_timestamp(ts: DateTime<Utc>) -> String {
    ts.format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

impl From<PostRow> for Post {
    fn from(row: PostRow) -> Self {
        Post {
            id: row.id,
            body: row.body,
            created_at: format_timestamp(row.created_at),
            author: row.username,
            like_count: row.like_count,
        }
    }
}

/// GET /feed: the 20 most recent posts with author + like count.
pub async fn feed(State(state): State<SharedState>) -> Result<impl IntoResponse, AppError> {
    let rows: Vec<PostRow> = sqlx::query_as(FEED_SQL).bind(FEED_SIZE).fetch_all(&state.pool).await?;
    Ok(Json(FeedResponse { posts: rows.into_iter().map(Post::from).collect() }))
}

/// GET /posts/{id}: one post with author + like count.
pub async fn get_post(
    State(state): State<SharedState>,
    PostId(id): PostId,
) -> Result<impl IntoResponse, AppError> {
    let row: PostRow = sqlx::query_as(POST_BY_ID_SQL)
        .bind(id)
        .fetch_optional(&state.pool)
        .await?
        .ok_or(AppError::PostNotFound)?;
    Ok(Json(PostResponse { post: Post::from(row) }))
}

/// POST /posts: create a post as the authenticated user. Body: `{"body":"..."}`.
pub async fn create_post(
    State(state): State<SharedState>,
    user: AuthUser,
    JsonBody(payload): JsonBody,
) -> Result<impl IntoResponse, AppError> {
    let body = payload
        .as_ref()
        .and_then(|v| v.get("body"))
        .and_then(Value::as_str)
        .map(str::trim)
        .unwrap_or("");
    if body.is_empty() {
        return Err(AppError::BodyRequired);
    }
    // JavaScript's String#length counts UTF-16 code units.
    if body.encode_utf16().count() > MAX_POST_BODY_LENGTH {
        return Err(AppError::BodyTooLong);
    }
    let (id, created_at): (i64, DateTime<Utc>) =
        sqlx::query_as("INSERT INTO posts (user_id, body) VALUES ($1, $2) RETURNING id, created_at")
            .bind(user.id)
            .bind(body)
            .fetch_one(&state.pool)
            .await?;
    let post = Post {
        id,
        body: body.to_string(),
        created_at: format_timestamp(created_at),
        author: user.username,
        like_count: 0,
    };
    Ok((StatusCode::CREATED, Json(PostResponse { post })))
}

/// POST /posts/{id}/like: idempotent like; a repeat is 200 with `already_liked: true`.
pub async fn like_post(
    State(state): State<SharedState>,
    user: AuthUser,
    PostId(post_id): PostId,
) -> Result<impl IntoResponse, AppError> {
    let result = sqlx::query(
        "INSERT INTO likes (user_id, post_id) VALUES ($1, $2)
     ON CONFLICT (user_id, post_id) DO NOTHING",
    )
    .bind(user.id)
    .bind(post_id)
    .execute(&state.pool)
    .await
    .map_err(|err| match &err {
        // 23503 = foreign_key_violation: the post (or user) doesn't exist.
        sqlx::Error::Database(db) if db.code().as_deref() == Some("23503") => AppError::PostNotFound,
        _ => AppError::Internal(err),
    })?;
    let inserted = result.rows_affected() > 0;
    let status = if inserted { StatusCode::CREATED } else { StatusCode::OK };
    Ok((status, Json(LikeResponse { liked: true, already_liked: !inserted, post_id })))
}
