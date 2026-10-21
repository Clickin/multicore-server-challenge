package com.example.feed;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PostController {

    private static final int FEED_SIZE = 20;
    private static final int MAX_POST_BODY_LENGTH = 500;

    private static final String POST_SELECT = """
            SELECT p.id, p.body, p.created_at, u.username,
                   (SELECT count(*) FROM likes l WHERE l.post_id = p.id)::int AS like_count
              FROM posts p
              JOIN users u ON u.id = p.user_id""";

    private static final RowMapper<Post> POST_ROW_MAPPER = (rs, rowNum) -> new Post(
            rs.getLong("id"),
            rs.getString("body"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getString("username"),
            rs.getInt("like_count"));

    record FeedResponse(List<Post> posts) {
    }

    record PostResponse(Post post) {
    }

    record LikeResponse(boolean liked, boolean alreadyLiked, long postId) {
    }

    private final JdbcTemplate jdbc;

    PostController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** GET /feed: the 20 most recent posts with author + like count. */
    @GetMapping("/feed")
    FeedResponse feed() {
        List<Post> posts = jdbc.query(POST_SELECT + " ORDER BY p.created_at DESC, p.id DESC LIMIT ?",
                POST_ROW_MAPPER, FEED_SIZE);
        return new FeedResponse(posts);
    }

    /** GET /posts/{id}: one post with author + like count. */
    @GetMapping("/posts/{id}")
    PostResponse getPost(@PathVariable String id) {
        long postId = parseId(id);
        List<Post> rows = jdbc.query(POST_SELECT + " WHERE p.id = ?", POST_ROW_MAPPER, postId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "post not found");
        }
        return new PostResponse(rows.get(0));
    }

    /** POST /posts: create a post as the authenticated user. Body: {"body":"..."} */
    @PostMapping("/posts")
    ResponseEntity<PostResponse> createPost(AuthUser user, @RequestBody(required = false) JsonNode request) {
        JsonNode bodyNode = request == null ? null : request.get("body");
        String body = bodyNode != null && bodyNode.isTextual() ? bodyNode.textValue().strip() : "";
        if (body.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "body is required");
        }
        if (body.length() > MAX_POST_BODY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "body must be at most " + MAX_POST_BODY_LENGTH + " characters");
        }
        Post post = jdbc.queryForObject(
                "INSERT INTO posts (user_id, body) VALUES (?, ?) RETURNING id, created_at",
                (rs, rowNum) -> new Post(rs.getLong("id"), body,
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), user.username(), 0),
                user.id(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(new PostResponse(post));
    }

    /**
     * POST /posts/{id}/like: like a post as the authenticated user. Idempotent: a repeat like is
     * a no-op (the PK on likes enforces it), reported as 200 with already_liked=true.
     */
    @PostMapping("/posts/{id}/like")
    ResponseEntity<LikeResponse> like(AuthUser user, @PathVariable String id) {
        long postId = parseId(id);
        int inserted;
        try {
            inserted = jdbc.update(
                    "INSERT INTO likes (user_id, post_id) VALUES (?, ?) ON CONFLICT (user_id, post_id) DO NOTHING",
                    user.id(), postId);
        } catch (DataIntegrityViolationException ex) {
            // SQLSTATE 23503 = foreign_key_violation: the post (or user) doesn't exist.
            if (ex.getMostSpecificCause() instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "post not found");
            }
            throw ex;
        }
        return ResponseEntity.status(inserted > 0 ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new LikeResponse(true, inserted == 0, postId));
    }

    private static long parseId(String raw) {
        long id;
        try {
            id = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid post id");
        }
        if (id <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid post id");
        }
        return id;
    }
}
