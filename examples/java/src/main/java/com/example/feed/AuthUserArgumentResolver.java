package com.example.feed;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Any controller method that declares an {@link AuthUser} parameter requires
 * {@code Authorization: Bearer <jwt>}. Verified with Auth0 java-jwt: HS256 only (the verifier rejects
 * any other "alg"), signature + exp checked. java-jwt accepts the 184-bit JWT_SECRET (jjwt refuses
 * HMAC keys under 256 bits). The verifier is immutable and thread-safe, built once.
 * Declared as the first handler parameter, so auth is checked before the path id is validated.
 */
@Component
class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER = "Bearer ";

    private final JWTVerifier verifier;

    AuthUserArgumentResolver(@Value("${JWT_SECRET}") String secret) {
        this.verifier = JWT.require(Algorithm.HMAC256(secret)).build();
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthUser.class;
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "missing bearer token");
        }
        String token = header.substring(BEARER.length()).trim();

        DecodedJWT jwt;
        try {
            jwt = verifier.verify(token);
        } catch (JWTVerificationException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid or expired token");
        }

        // asString() is null unless the claim is a JSON string.
        String sub = jwt.getClaim("sub").asString();
        String username = jwt.getClaim("username").asString();
        long id = 0;
        if (sub != null && !sub.isEmpty() && sub.chars().allMatch(c -> c >= '0' && c <= '9')) {
            try {
                id = Long.parseLong(sub);
            } catch (NumberFormatException ex) {
                id = 0;
            }
        }
        if (id <= 0 || username == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid token payload");
        }
        return new AuthUser(id, username);
    }
}
