package com.example.feed;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Serialized as {"id","body","created_at","author","like_count"} (record order + SNAKE_CASE).
 * created_at is a Postgres TIMESTAMPTZ (microseconds), read as an Instant and formatted by Jackson in
 * UTC with millisecond precision. DateTimeFormatter's SSS prints the first three fraction digits, i.e.
 * it truncates (does not round), like JavaScript's Date.
 */
public record Post(
        long id,
        String body,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
        Instant createdAt,
        String author,
        int likeCount) {
}
