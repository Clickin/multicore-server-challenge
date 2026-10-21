package com.example.feed;

/** The authenticated caller, resolved from the Bearer JWT by {@link AuthUserArgumentResolver}. */
public record AuthUser(long id, String username) {
}
