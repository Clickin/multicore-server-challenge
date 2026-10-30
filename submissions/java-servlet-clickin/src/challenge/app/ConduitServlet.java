package challenge.app;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import challenge.BcryptPool;
import challenge.JsonWriter;
import challenge.Jwt;
import challenge.app.Helpers.Body;
import challenge.app.Helpers.Invalid;
import challenge.app.Helpers.Params;
import challenge.http.Task;
import challenge.pg.Completions;
import challenge.pg.PgDispatcher;
import challenge.pg.Query;
import challenge.pg.Stmt;
import challenge.pg.Unit;
import challenge.pg.Unit.Callback;
import challenge.servlet.Request;
import challenge.servlet.Response;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.ConduitServlet.Route.*;
import static challenge.app.ConduitServlet.Verb.*;

public final class ConduitServlet extends HttpServlet {
    enum Verb {
        OTHER,
        GET,
        POST,
        PUT,
        DELETE
    }

    enum Auth {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    // Properties are constructor fields, not switches: a field load per request instead of a jump table,
    // and a new route still fails compilation until every property is given.
    enum Route {
        // (statement, auth, writes, targeted)
        TAGS(Stmt.TAGS, Auth.NONE, false, false),
        REGISTER(Stmt.REGISTER, Auth.NONE, true, false),
        LOGIN(Stmt.LOGIN, Auth.NONE, false, false),
        USER(Stmt.USER, Auth.REQUIRED, false, false),
        UPDATE(Stmt.UPDATE_USER, Auth.REQUIRED, true, false),
        PROFILE(Stmt.PROFILE, Auth.OPTIONAL, false, true),
        FOLLOW(Stmt.FOLLOW, Auth.REQUIRED, true, true),
        UNFOLLOW(Stmt.UNFOLLOW, Auth.REQUIRED, true, true),
        ARTICLES(Stmt.ARTICLES, Auth.OPTIONAL, false, false),
        ARTICLES_TAG(Stmt.ARTICLES_TAG, Auth.OPTIONAL, false, false),
        ARTICLES_AUTHOR(Stmt.ARTICLES_AUTHOR, Auth.OPTIONAL, false, false),
        ARTICLES_FAVORITED(Stmt.ARTICLES_FAVORITED, Auth.OPTIONAL, false, false),
        FEED(Stmt.FEED, Auth.REQUIRED, false, false),
        ARTICLE(Stmt.ARTICLE, Auth.OPTIONAL, false, true),
        COMMENTS(Stmt.COMMENTS, Auth.OPTIONAL, false, true),
        CREATE_ARTICLE(Stmt.CREATE_ARTICLE, Auth.REQUIRED, true, false),
        UPDATE_ARTICLE(Stmt.UPDATE_ARTICLE, Auth.REQUIRED, true, true),
        DELETE_ARTICLE(Stmt.DELETE_ARTICLE, Auth.REQUIRED, true, true),
        FAVORITE(Stmt.FAVORITE, Auth.REQUIRED, true, true),
        UNFAVORITE(Stmt.UNFAVORITE, Auth.REQUIRED, true, true),
        CREATE_COMMENT(Stmt.CREATE_COMMENT, Auth.REQUIRED, true, true),
        DELETE_COMMENT(Stmt.DELETE_COMMENT, Auth.REQUIRED, true, true);
        final int stmt;
        final Auth auth;
        final boolean writes, targeted;

        Route(int stmt, Auth auth, boolean writes,
              boolean targeted) {
            this.stmt = stmt;
            this.auth = auth;
            this.writes = writes;
            this.targeted = targeted;
        }
    }

    // Routing is branchless: path words and the method are hashed (first 8 bytes) into token ids, a mask
    // zeroes unknown words, and the route is a single ROUTE_TABLE load keyed by (resource, sub-word, segment count, verb).
    private static final String[] WORDS = {
        null,
        "api",
        "tags",
        "user",
        "users",
        "profiles",
        "articles",
        "login",
        "feed",
        "follow",
        "favorite",
        "comments",
        "GET",
        "POST",
        "PUT",
        "DELETE"
    };
    private static final String[] RESOURCE_WORDS = {null, "tags", "user", "users", "profiles", "articles"}, SUB_WORDS = {null, "login", "feed", "follow", "favorite", "comments"};
    private static final int API = 1, COUNTS = 7;
    private static final int[] LAST_SEGMENT = {1, 1, 1, 2, 3, 3,
            3};
    private static final long HASH = 0x9e3779b97f4a7c4bL;
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long[] WORD = new long[WORDS.length];
    private static final int[] LENGTH = new int[WORDS.length];
    private static final byte[] TOKEN = new byte[32], RESOURCE = new byte[WORDS.length], SUB = new byte[WORDS.length], VERB = new byte[WORDS.length];
    private static final Verb[] VERBS = Verb.values();
    private static final Route[] ROUTES = new Route[Route.values().length + 1];
    private static final byte[] ROUTE_TABLE = new byte[RESOURCE_WORDS.length * SUB_WORDS.length * COUNTS * VERBS.length];

    static {
        for (int t = 1; t < WORDS.length; t++) {
            byte[] b = WORDS[t].getBytes(StandardCharsets.US_ASCII);
            long w = 0;
            for (int i = 0; i < b.length; i++) {
                w |= (b[i] & 255L) << (8 * i);
            }
            int h = hash(w);
            if (TOKEN[h] != 0) {
                throw new ExceptionInInitializerError("route word hash collision: " + WORDS[t]);
            }
            TOKEN[h] = (byte) t;
            WORD[t] = w;
            LENGTH[t] = b.length;
        }
        for (int i = 1; i < RESOURCE_WORDS.length; i++) {
            RESOURCE[id(RESOURCE_WORDS[i])] = (byte) i;
        }
        for (int i = 1; i < SUB_WORDS.length; i++) {
            SUB[id(SUB_WORDS[i])] = (byte) i;
        }
        for (Verb v : VERBS) {
            if (v != OTHER) {
                VERB[id(v.name())] = (byte) v.ordinal();
            }
        }
        for (Route r : Route.values()) {
            ROUTES[r.ordinal() + 1] = r;
        }
        put("tags", 2, null, GET, TAGS);
        put("user", 2, null, GET, USER);
        put("user", 2, null, PUT, UPDATE);
        put("users", 2, null, POST, REGISTER);
        put("users", 3, "login", POST, LOGIN);
        put("profiles", 3, null, GET, PROFILE);
        put("profiles", 4, "follow", POST, FOLLOW);
        put("profiles", 4, "follow", DELETE, UNFOLLOW);
        put("articles", 2, null, GET, ARTICLES);
        put("articles", 2, null, POST, CREATE_ARTICLE);
        put("articles", 3, null, GET, ARTICLE);
        put("articles", 3, "feed", GET, FEED);
        put("articles", 3, null, PUT, UPDATE_ARTICLE);
        put("articles", 3, null, DELETE, DELETE_ARTICLE);
        put("articles", 4, "favorite", POST, FAVORITE);
        put("articles", 4, "favorite", DELETE, UNFAVORITE);
        put("articles", 4, "comments", GET, COMMENTS);
        put("articles", 4, "comments", POST, CREATE_COMMENT);
        put("articles", 5, "comments", DELETE, DELETE_COMMENT);
    }

    private static int id(String word) {
        for (int t = 1; t < WORDS.length; t++) {
            if (WORDS[t].equals(word)) {
                return t;
            }
        }
        throw new IllegalArgumentException(word);
    }

    // A null sub-word fills every sub-word slot: that segment is a slug or username, not a keyword.
    private static void put(String resource, int count, String sub,
                            Verb verb, Route route) {
        for (int s = 0; s < SUB_WORDS.length; s++) {
            if (sub == null || s == SUB[id(sub)]) {
                ROUTE_TABLE[key(RESOURCE[id(resource)], s, count, verb.ordinal())] = (byte) (route.ordinal() + 1);
            }
        }
    }

    private static int key(int resource, int sub, int count,
                           int verb) {
        return ((resource * SUB_WORDS.length + sub) * COUNTS + count) * VERBS.length + verb;
    }

    private static int hash(long w) {
        return (int) (w * HASH >>> 59);
    }

    private static int token(byte[] b, int a, int e) {
        int len = e - a;
        long w = (long) LONG.get(b, Math.max(0, Math.min(a, b.length - 8))) & (-1L >>> ((8 - Math.max(1, Math.min(len, 8))) << 3));
        int t = TOKEN[hash(w)];
        long d = (WORD[t] ^ w) | (LENGTH[t] ^ len);
        return t & (int) ~((d | -d) >> 63);
    }

    static Verb verb(Request r) {
        return VERBS[VERB[token(r.bytes(), 0, r.pathOffset() - 1)]];
    }

    static Route route(Request r, int[] start, int[] end,
                       int count, Verb verb) {
        byte[] b = r.bytes();
        int c = Math.max(0, Math.min(count, COUNTS - 1)), last = LAST_SEGMENT[c];
        int d = token(b, start[0], end[0]) ^ API, api = ~((d | -d) >> 31);
        int resource = RESOURCE[token(b, start[1], end[1])] & api;
        return ROUTES[ROUTE_TABLE[key(resource, SUB[token(b, start[last], end[last])], c, verb.ordinal())]];
    }

    private final PgDispatcher dispatcher;
    private final BcryptPool bcrypt;
    private final String secret;

    public ConduitServlet(PgDispatcher dispatcher, BcryptPool bcrypt, String secret) {
        this.dispatcher = dispatcher;
        this.bcrypt = bcrypt;
        this.secret = secret;
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Request req = (Request) request;
        Response res = (Response) response;
        int[] start = req.segmentStarts, end = req.segmentEnds;
        int count = segments(req, start, end);
        Verb verb = verb(req);
        if (count == 1 && eq(req, start[0], end[0], "__async") && verb == GET) {
            AsyncContext async = req.startAsync();
            req.loop().post(new Task() {
                public void run() {
                    async.complete();
                }
            });
            return;
        }
        if (count == 1 && eq(req, start[0], end[0], "__echo") && verb == POST) {
            res.getOutputStream().write(req.bytes(), req.bodyOffset(), req.bodyLength());
            return;
        }
        Route route = route(req, start, end, count, verb);
        if (route == null) {
            Helpers.error(res, 404, "not found");
            return;
        }
        Slot slot = (Slot) req.applicationSlot;
        if (slot == null) {
            req.applicationSlot = slot = new Slot(req);
        }
        long viewer = 0;
        Auth auth = route.auth;
        if (auth != Auth.NONE) {
            viewer = Helpers.viewer(req, slot.jwt, auth == Auth.REQUIRED);
            if (viewer < 0) {
                Helpers.error(res, 401, "unauthorized");
                return;
            }
        }
        String username = null, email = null, password = null, bio = null, image = null, title = null, description = null, bodyText = null;
        String[] tags = null;
        long commentId = 0;
        int limit = 20, offset = 0;
        try {
            if (route == ARTICLES || route == FEED) {
                slot.params.parse(req);
                limit = slot.params.page(0, 20, 1, 100);
                offset = slot.params.page(1, 0, 0, Integer.MAX_VALUE);
                if (route == ARTICLES) {
                    if (slot.params.length(2) > 0) {
                        route = ARTICLES_TAG;
                    } else if (slot.params.length(3) > 0) {
                        route = ARTICLES_AUTHOR;
                    } else if (slot.params.length(4) > 0) {
                        route = ARTICLES_FAVORITED;
                    }
                }
            }
        } catch (Invalid | IllegalArgumentException e) {
            Helpers.error(res, 422, "invalid parameters");
            return;
        }
        try {
            switch (route) {
                case REGISTER, LOGIN, UPDATE -> {
                    Body body = Helpers.body(req, "user");
                    if (route != LOGIN) {
                        username = Helpers.field(body, "username", route == REGISTER, true);
                    }
                    email = Helpers.field(body, "email", route != UPDATE, true);
                    password = Helpers.password(body, route != UPDATE);
                    if (route == UPDATE) {
                        bio = Helpers.field(body, "bio", false, false);
                        image = Helpers.field(body, "image", false, false);
                    }
                }
                case CREATE_ARTICLE, UPDATE_ARTICLE -> {
                    Body body = Helpers.body(req, "article");
                    boolean required = route == CREATE_ARTICLE;
                    title = Helpers.field(body, "title", required, true);
                    description = Helpers.field(body, "description", required, true);
                    bodyText = Helpers.field(body, "body", required, true);
                    if (required) {
                        tags = Helpers.stringArray(body, "tagList");
                        if (tags == null) {
                            tags = new String[0];
                        }
                    }
                }
                case CREATE_COMMENT -> bodyText = Helpers.field(Helpers.body(req, "comment"), "body", true, true);
                default -> {}
            }
        } catch (Invalid e) {
            Helpers.error(res, 422, "invalid body");
            return;
        }
        if (route == DELETE_COMMENT) {
            try {
                commentId = Long.parseLong(new String(req.bytes(), start[4], end[4] - start[4], StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                Helpers.error(res, 404, "not found");
                return;
            }
        }
        slot.title = title;
        slot.description = description;
        slot.bodyText = bodyText;
        slot.tags = tags;
        slot.commentId = commentId;
        slot.route = route;
        slot.response = res;
        slot.viewer = viewer;
        slot.username = username;
        slot.email = email;
        slot.password = password;
        slot.bio = bio;
        slot.image = image;
        slot.limit = limit;
        slot.offset = offset;
        slot.tokenOffset = req.authorizationOffset() < 0 ? 0 : req.authorizationOffset() + 6;
        slot.tokenLength = req.authorizationOffset() < 0 ? 0 : req.authorizationLength() - 6;
        if (route.targeted) {
            slot.targetOffset = start[2];
            slot.targetLength = end[2] - start[2];
            slot.targetValid = utf8(req.bytes(), slot.targetOffset, slot.targetLength);
        }
        slot.async = req.startAsync();
        slot.start();
    }

    private static boolean utf8(byte[] b, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end; ) {
            int c = b[i++] & 255;
            if (c < 128) {
                continue;
            }
            int n, min, value;
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

    private static boolean eq(Request r, int a, int b,
            String s) {
        if (b - a != s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (r.bytes()[a + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static final long SLASHES = 0x2f2f2f2f2f2f2f2fL, LOW7 = 0x7f7f7f7f7f7f7f7fL;

    // Branchless split: '/' bytes are found 8 at a time (exact zero-byte test on w^SLASHES), stored to end[] with
    // unconditional writes, and every invalid case is folded into a mask. Only the word loop's trip count depends on
    // the input. Reading 8 bytes at any i<limit stays in bounds: Loop only accepts request lines ending " HTTP/1.1".
    // More than 4 slashes in one word implies an empty segment, so leftover bits mark the path invalid.
    static int segments(Request r, int[] start, int[] end) {
        byte[] b = r.bytes();
        int p = r.pathOffset() + 1, limit = r.pathOffset() + r.pathLength(), last = end.length - 1, n = 0;
        long leftover = 0;
        for (int i = p; i < limit; i += 8) {
            long x = (long) LONG.get(b, i) ^ SLASHES, m = ~(((x & LOW7) + LOW7) | x | LOW7) & (-1L >>> ((8 - Math.min(limit - i, 8)) << 3));
            for (int k = 0; k < 4; k++) {
                end[Math.min(n, last)] = i + (Long.numberOfTrailingZeros(m) >>> 3);
                n += (int) ((m | -m) >>> 63);
                m &= m - 1;
            }
            leftover |= m;
        }
        end[Math.min(n, last)] = limit;
        start[0] = p;
        for (int k = 1; k < start.length; k++) {
            start[k] = end[k - 1] + 1;
        }
        int count = (n + 1) & ((p - limit) >> 31), invalid = (int) ((leftover | -leftover) >> 63) | ((start.length - count) >> 31);
        for (int k = 0; k < start.length; k++) {
            int d = end[k] - start[k];
            invalid |= ~((d | -d) >> 31) & ((k - count) >> 31);
        }
        return count | invalid;
    }

    private final class Slot extends Task implements Completions, Callback {
        final Request request;
        final Jwt jwt;
        final Query query = new Query(0, false);
        Query secondary;
        final Query[] one = {query}, two = new Query[2];
        final Unit unit;
        final Params params = new Params();
        final byte[] signed = new byte[256];
        Response response;
        AsyncContext async;
        Route route;
        int phase, tokenOffset, tokenLength, targetOffset, targetLength;
        long viewer;
        boolean targetValid;
        String username, email, password, bio, image, title, description, bodyText;
        String[] tags;
        long commentId;
        int limit, offset;

        Slot(Request request) {
            this.request = request;
            try {
                jwt = request.loop() == null ? new Jwt(secret) : request.loop().jwt(secret);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            unit = new Unit(this, this, false, false, one);
        }

        void start() {
            boolean write = route.writes;
            query.prepare(route.stmt, write);
            boolean dual = route == CREATE_ARTICLE || route == FAVORITE || route == UNFAVORITE;
            if (dual) {
                if (secondary == null) {
                    secondary = new Query(0, true);
                }
                secondary.prepare(route == CREATE_ARTICLE ? Stmt.INSERT_TAGS : Stmt.ARTICLE, true);
                two[0] = route == CREATE_ARTICLE ? secondary : query;
                two[1] = route == CREATE_ARTICLE ? query : secondary;
            } else if (secondary != null) {
                secondary.reset();
            }
            unit.prepare(false, write, dual ? two : one);
            phase = 0;
            Query q = query;
            switch (route) {
                case TAGS -> dispatcher.submit(unit);
                case REGISTER -> {
                    unit.bcryptPassword = password;
                    phase = 1;
                    bcrypt.submit(unit);
                }
                case LOGIN -> {
                    q.text(email);
                    dispatcher.submit(unit);
                }
                case USER -> {
                    q.int8(viewer);
                    dispatcher.submit(unit);
                }
                case UPDATE -> {
                    if (password != null) {
                        unit.bcryptPassword = password;
                        phase = 1;
                        bcrypt.submit(unit);
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
                    dispatcher.submit(unit);
                }
                case UPDATE_ARTICLE -> {
                    target(q.int8(viewer))
                            .text(title)
                            .text(description)
                            .text(bodyText)
                            .text(title == null ? null : Formats.slugify(title));
                    dispatcher.submit(unit);
                }
                case DELETE_ARTICLE -> {
                    target(q.int8(viewer));
                    dispatcher.submit(unit);
                }
                case FAVORITE, UNFAVORITE -> {
                    target(q.int8(viewer));
                    target(unit.queries[1].int8(viewer));
                    dispatcher.submit(unit);
                }
                case CREATE_COMMENT -> {
                    target(q.int8(viewer)).text(bodyText);
                    dispatcher.submit(unit);
                }
                case DELETE_COMMENT -> {
                    target(q.int8(viewer)).int8(commentId);
                    dispatcher.submit(unit);
                }
                case ARTICLE, COMMENTS, PROFILE, FOLLOW, UNFOLLOW -> {
                    target(q.int8(viewer));
                    dispatcher.submit(unit);
                }
                case ARTICLES, FEED -> {
                    q.int8(viewer)
                     .int4(limit)
                     .int4(offset);
                    dispatcher.submit(unit);
                }
                case ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED -> {
                    int p = switch (route) {
                        case ARTICLES_TAG -> 2;
                        case ARTICLES_AUTHOR -> 3;
                        default -> 4;
                    };
                    q.int8(viewer)
                     .int4(limit)
                     .int4(offset)
                     .text(params.bytes(), params.offset(p), params.length(p));
                    dispatcher.submit(unit);
                }
                default -> throw new AssertionError();
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
            dispatcher.submit(unit);
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
                    finishError(500, "password error");
                    return;
                }
                phase = 0;
                if (route == REGISTER) {
                    query.text(username)
                         .text(email)
                         .text(unit.bcryptHash);
                    dispatcher.submit(unit);
                    return;
                }
                if (route == UPDATE) {
                    update(unit.bcryptHash);
                    return;
                }
                if (!unit.bcryptMatches) {
                    finishError(401, "invalid credentials");
                    return;
                }
                writeUser();
                return;
            }
            Unit u = unit;
            Query q = query;
            if (u.errorMessage != null) {
                finishError("23505".equals(u.sqlState) ? 422 : 500, "database error");
                return;
            }
            switch (route) {
                case TAGS -> {
                    response.setContentType("application/json");
                    JsonWriter out = response.jsonOut();
                    Helpers.raw(out, "{\"tags\":[");
                    for (int i = 0; i < q.rowCount(); i++) {
                        if (i != 0) {
                            out.put(',');
                        }
                        Helpers.column(out, q, i, 0);
                    }
                    Helpers.raw(out, "]}");
                    async.complete();
                }
                case ARTICLES, ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED, FEED -> {
                    response.setContentType("application/json");
                    JsonWriter out = response.jsonOut();
                    Helpers.raw(out, "{\"articles\":[");
                    for (int i = 0; i < q.rowCount(); i++) {
                        if (q.isNull(i, 0)) {
                            continue;
                        }
                        if (i > 0) {
                            out.put(',');
                        }
                        Helpers.article(out, q, i, false);
                    }
                    Helpers.raw(out, "],\"articlesCount\":");
                    out.number(q.int8At(0, 13));
                    out.put('}');
                    async.complete();
                }
                case LOGIN -> {
                    if (!missing(q, 401)) {
                        unit.bcryptPassword = password;
                        unit.bcryptHash = new String(q.bytes(), q.offset(0, 5), q.length(0, 5),
                                StandardCharsets.UTF_8);
                        phase = 1;
                        bcrypt.submit(unit);
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
                    response.setContentType("application/json");
                    JsonWriter out = response.jsonOut();
                    Helpers.raw(out, "{\"profile\":");
                    Helpers.profile(out, q, 0, 0,
                            route == PROFILE ? q.boolAt(0, 3) : route == FOLLOW);
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
                        finishError(403, "forbidden");
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
                        finishError(403, "forbidden");
                    } else {
                        response.setStatus(204);
                        async.complete();
                    }
                }
                case COMMENTS -> {
                    if (missing(q, 404)) {
                        return;
                    }
                    response.setContentType("application/json");
                    JsonWriter out = response.jsonOut();
                    Helpers.raw(out, "{\"comments\":[");
                    for (int i = 0; i < q.rowCount(); i++) {
                        if (q.isNull(i, 0)) {
                            continue;
                        }
                        if (i > 0) {
                            out.put(',');
                        }
                        Helpers.comment(out, q, i, q.boolAt(i, 7));
                    }
                    Helpers.raw(out, "]}");
                    async.complete();
                }
                case CREATE_COMMENT -> {
                    if (missing(q, 404)) {
                        return;
                    }
                    response.setStatus(201);
                    response.setContentType("application/json");
                    JsonWriter out = response.jsonOut();
                    Helpers.raw(out, "{\"comment\":");
                    Helpers.comment(out, q, 0, false);
                    out.put('}');
                    async.complete();
                }
            }
        }

        boolean missing(Query q, int status) {
            if (q.rowCount() != 0) {
                return false;
            }
            finishError(status, "not found");
            return true;
        }

        void writeArticle(Query q) {
            if (route == CREATE_ARTICLE) {
                response.setStatus(201);
            }
            response.setContentType("application/json");
            JsonWriter out = response.jsonOut();
            Helpers.raw(out, "{\"article\":");
            Helpers.article(out, q, 0, true);
            out.put('}');
            async.complete();
        }

        void writeUser() {
            Query q = query;
            byte[] token = request.bytes();
            int at = tokenOffset, len = tokenLength;
            if (route == REGISTER || route == LOGIN) {
                at = 0;
                len = jwt.sign(q.int8At(0, 0), System.currentTimeMillis() / 1000, signed, 0);
                token = signed;
            }
            response.setContentType("application/json");
            if (route == REGISTER) {
                response.setStatus(201);
            }
            JsonWriter out = response.jsonOut();
            Helpers.raw(out, "{\"user\":");
            Helpers.user(out, q, 0, token, at, len);
            out.put('}');
            async.complete();
        }

        void finishError(int status, String message) {
            Helpers.error(response, status, message);
            async.complete();
        }
    }
}
