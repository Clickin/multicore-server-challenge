package challenge.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import challenge.JsonWriter;
import challenge.Jwt;
import challenge.app.Conduit.Services;
import challenge.app.Inputs.Params;
import challenge.http.Task;
import challenge.pg.Completions;
import challenge.pg.Query;
import challenge.pg.Stmt;
import challenge.pg.Unit;
import challenge.pg.Unit.Callback;
import challenge.servlet.Request;
import challenge.servlet.Response;
import jakarta.servlet.AsyncContext;

import static challenge.app.Op.ARTICLE;
import static challenge.app.Op.ARTICLES;
import static challenge.app.Op.ARTICLES_AUTHOR;
import static challenge.app.Op.ARTICLES_FAVORITED;
import static challenge.app.Op.ARTICLES_TAG;
import static challenge.app.Op.COMMENTS;
import static challenge.app.Op.CREATE_ARTICLE;
import static challenge.app.Op.CREATE_COMMENT;
import static challenge.app.Op.DELETE_ARTICLE;
import static challenge.app.Op.DELETE_COMMENT;
import static challenge.app.Op.FAVORITE;
import static challenge.app.Op.FEED;
import static challenge.app.Op.FOLLOW;
import static challenge.app.Op.LOGIN;
import static challenge.app.Op.PROFILE;
import static challenge.app.Op.REGISTER;
import static challenge.app.Op.TAGS;
import static challenge.app.Op.UNFAVORITE;
import static challenge.app.Op.UNFOLLOW;
import static challenge.app.Op.UPDATE;
import static challenge.app.Op.UPDATE_ARTICLE;
import static challenge.app.Op.USER;

/**
 * One request's database and bcrypt work, pooled per connection exchange. A
 * servlet fills the inputs, {@link #start} binds and submits them, and {@link
 * #run} renders the result on the loop thread and completes the async request.
 */
final class Call extends Task implements Completions, Callback {
    final Request request;
    final Services services;
    final Jwt jwt;
    final Query query = new Query(0, false);
    Query secondary;
    final Query[] one = {query};
    final Query[] two = new Query[2];
    final Unit unit;
    final Params params = new Params();
    final byte[] signed = new byte[256];
    Response response;
    AsyncContext async;
    Op op;
    int phase;
    int tokenOffset;
    int tokenLength;
    int targetOffset;
    int targetLength;
    long viewer;
    boolean targetValid;
    String username;
    String email;
    String password;
    String bio;
    String image;
    String title;
    String description;
    String bodyText;
    String[] tags;
    long commentId;
    int limit;
    int offset;

    private Call(Request request, Services services) {
        this.request = request;
        this.services = services;
        try {
            jwt = request.loop() == null ? new Jwt(services.secret()) : request.loop().jwt(services.secret());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        unit = new Unit(this, this, false, false, one);
    }

    /** The exchange's pooled call, created on first use. */
    static Call of(Request req, Services services) {
        Call call = (Call) req.applicationSlot;
        if (call == null) {
            req.applicationSlot = call = new Call(req, services);
        }
        return call;
    }

    void reset(Op op, Request req, Response response,
               long viewer) {
        this.op = op;
        this.response = response;
        this.viewer = viewer;
        username = email = password = bio = image = title = description = bodyText = null;
        tags = null;
        commentId = 0;
        limit = 20;
        offset = 0;
        tokenOffset = req.authorizationOffset() < 0 ? 0 : req.authorizationOffset() + 6;
        tokenLength = req.authorizationOffset() < 0 ? 0 : req.authorizationLength() - 6;
        if (op.targeted) {
            targetOffset = req.segmentStarts[2];
            targetLength = req.segmentEnds[2] - req.segmentStarts[2];
            targetValid = utf8(req.bytes(), targetOffset, targetLength);
        }
    }

    void start() {
        async = request.startAsync();
        boolean write = op.writes;
        query.prepare(op.stmt, write);
        boolean dual = op == CREATE_ARTICLE || op == FAVORITE || op == UNFAVORITE;
        if (dual) {
            if (secondary == null) {
                secondary = new Query(0, true);
            }
            secondary.prepare(op == CREATE_ARTICLE ? Stmt.INSERT_TAGS : Stmt.ARTICLE, true);
            two[0] = op == CREATE_ARTICLE ? secondary : query;
            two[1] = op == CREATE_ARTICLE ? query : secondary;
        } else if (secondary != null) {
            secondary.reset();
        }
        unit.prepare(false, write, dual ? two : one);
        phase = 0;
        Query q = query;
        switch (op) {
            case TAGS -> services.dispatcher().submit(unit);
            case REGISTER -> {
                unit.bcryptPassword = password;
                phase = 1;
                services.bcrypt().submit(unit);
            }
            case LOGIN -> {
                q.text(email);
                services.dispatcher().submit(unit);
            }
            case USER -> {
                q.int8(viewer);
                services.dispatcher().submit(unit);
            }
            case UPDATE -> {
                if (password != null) {
                    unit.bcryptPassword = password;
                    phase = 1;
                    services.bcrypt().submit(unit);
                } else {
                    update(null);
                }
            }
            case CREATE_ARTICLE -> {
                unit.queries[0].textArray(tags);
                q.int8(viewer)
                 .text(title)
                 .text(description)
                 .text(bodyText)
                 .textArray(tags)
                 .text(Formats.slugify(title));
                services.dispatcher().submit(unit);
            }
            case UPDATE_ARTICLE -> {
                target(q.int8(viewer))
                        .text(title)
                        .text(description)
                        .text(bodyText)
                        .text(title == null ? null : Formats.slugify(title));
                services.dispatcher().submit(unit);
            }
            case DELETE_ARTICLE -> {
                target(q.int8(viewer));
                services.dispatcher().submit(unit);
            }
            case FAVORITE, UNFAVORITE -> {
                target(q.int8(viewer));
                target(unit.queries[1].int8(viewer));
                services.dispatcher().submit(unit);
            }
            case CREATE_COMMENT -> {
                target(q.int8(viewer)).text(bodyText);
                services.dispatcher().submit(unit);
            }
            case DELETE_COMMENT -> {
                target(q.int8(viewer)).int8(commentId);
                services.dispatcher().submit(unit);
            }
            case ARTICLE, COMMENTS, PROFILE, FOLLOW, UNFOLLOW -> {
                target(q.int8(viewer));
                services.dispatcher().submit(unit);
            }
            case ARTICLES, FEED -> {
                q.int8(viewer)
                 .int4(limit)
                 .int4(offset);
                services.dispatcher().submit(unit);
            }
            case ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED -> {
                int p = switch (op) {
                    case ARTICLES_TAG -> 2;
                    case ARTICLES_AUTHOR -> 3;
                    default -> 4;
                };
                q.int8(viewer)
                 .int4(limit)
                 .int4(offset)
                 .text(params.bytes(), params.offset(p), params.length(p));
                services.dispatcher().submit(unit);
            }
            default -> throw new AssertionError(op);
        }
    }

    Query target(Query q) {
        return targetValid ? q.text(request.bytes(), targetOffset, targetLength) : q.text(new String(request.bytes(), targetOffset, targetLength, StandardCharsets.UTF_8));
    }

    void update(String hash) {
        query.int8(viewer)
             .text(email)
             .text(username)
             .text(hash)
             .text(bio)
             .text(image);
        services.dispatcher().submit(unit);
    }

    public void post(Unit ignored) {
        request.loop().post(this);
    }

    public void done(Unit ignored) {
        run();
    }

    public void run() {
        request.loop().assertLoopThread();
        if (phase == 1) {
            if (unit.errorMessage != null) {
                fail(500, "password error");
                return;
            }
            phase = 0;
            if (op == REGISTER) {
                query.text(username)
                     .text(email)
                     .text(unit.bcryptHash);
                services.dispatcher().submit(unit);
                return;
            }
            if (op == UPDATE) {
                update(unit.bcryptHash);
                return;
            }
            if (!unit.bcryptMatches) {
                fail(401, "invalid credentials");
                return;
            }
            writeUser();
            return;
        }
        Unit u = unit;
        Query q = query;
        if (u.errorMessage != null) {
            fail("23505".equals(u.sqlState) ? 422 : 500, "database error");
            return;
        }
        switch (op) {
            case TAGS -> {
                Views.tags(json(), q);
                async.complete();
            }
            case ARTICLES, ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED, FEED -> {
                Views.articles(json(), q);
                async.complete();
            }
            case LOGIN -> {
                if (!missing(q, 401)) {
                    unit.bcryptPassword = password;
                    unit.bcryptHash = new String(q.bytes(), q.offset(0, 5), q.length(0, 5),
                            StandardCharsets.UTF_8);
                    phase = 1;
                    services.bcrypt().submit(unit);
                }
            }
            case REGISTER, USER, UPDATE -> {
                if (!missing(q, 401)) {
                    writeUser();
                }
            }
            case PROFILE, FOLLOW, UNFOLLOW -> {
                if (missing(q, 404)) {
                    return;
                }
                JsonWriter out = json();
                Views.raw(out, "{\"profile\":");
                Views.profile(out, q, 0, 0,
                        op == PROFILE ? q.boolAt(0, 3) : op == FOLLOW);
                out.put('}');
                async.complete();
            }
            case ARTICLE -> {
                if (!missing(q, 404)) {
                    writeArticle(q);
                }
            }
            case CREATE_ARTICLE -> {
                if (!missing(q, 401)) {
                    writeArticle(q);
                }
            }
            case UPDATE_ARTICLE -> {
                if (missing(q, 404)) {
                    return;
                }
                if (q.isNull(0, 0)) {
                    fail(403, "forbidden");
                } else {
                    writeArticle(q);
                }
            }
            case FAVORITE, UNFAVORITE -> {
                Query article = u.queries[1];
                if (!missing(article, 404)) {
                    writeArticle(article);
                }
            }
            case DELETE_ARTICLE, DELETE_COMMENT -> {
                if (missing(q, 404)) {
                    return;
                }
                if (!q.boolAt(0, 0)) {
                    fail(403, "forbidden");
                } else {
                    response.setStatus(204);
                    async.complete();
                }
            }
            case COMMENTS -> {
                if (!missing(q, 404)) {
                    Views.comments(json(), q);
                    async.complete();
                }
            }
            case CREATE_COMMENT -> {
                if (missing(q, 404)) {
                    return;
                }
                response.setStatus(201);
                JsonWriter out = json();
                Views.raw(out, "{\"comment\":");
                Views.comment(out, q, 0, false);
                out.put('}');
                async.complete();
            }
        }
    }

    private JsonWriter json() {
        response.setContentType("application/json");
        return response.jsonOut();
    }

    boolean missing(Query q, int status) {
        if (q.rowCount() != 0) {
            return false;
        }
        fail(status, "not found");
        return true;
    }

    void writeArticle(Query q) {
        if (op == CREATE_ARTICLE) {
            response.setStatus(201);
        }
        JsonWriter out = json();
        Views.raw(out, "{\"article\":");
        Views.article(out, q, 0, true);
        out.put('}');
        async.complete();
    }

    void writeUser() {
        Query q = query;
        byte[] token = request.bytes();
        int at = tokenOffset;
        int len = tokenLength;
        if (op == REGISTER || op == LOGIN) {
            at = 0;
            len = jwt.sign(q.int8At(0, 0), System.currentTimeMillis() / 1000, signed, 0);
            token = signed;
        }
        if (op == REGISTER) {
            response.setStatus(201);
        }
        JsonWriter out = json();
        Views.raw(out, "{\"user\":");
        Views.user(out, q, 0, token, at, len);
        out.put('}');
        async.complete();
    }

    void fail(int status, String message) {
        try {
            response.sendError(status, message);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        async.complete();
    }

    private static boolean utf8(byte[] b, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end; ) {
            int c = b[i++] & 255;
            if (c < 128) {
                continue;
            }
            int n;
            int min;
            int value;
            if (c >= 0xc2 && c <= 0xdf) {
                n = 1;
                min = 0x80;
                value = c & 31;
            } else if (c >= 0xe0 && c <= 0xef) {
                n = 2;
                min = 0x800;
                value = c & 15;
            } else if (c >= 0xf0 && c <= 0xf4) {
                n = 3;
                min = 0x10000;
                value = c & 7;
            } else {
                return false;
            }
            if (end - i < n) {
                return false;
            }
            while (n-- > 0) {
                int x = b[i++] & 255;
                if ((x & 0xc0) != 0x80) {
                    return false;
                }
                value = value << 6 | (x & 63);
            }
            if (value < min
                    || value > 0x10ffff
                    || value >= 0xd800 && value <= 0xdfff) {
                return false;
            }
        }
        return true;
    }
}
