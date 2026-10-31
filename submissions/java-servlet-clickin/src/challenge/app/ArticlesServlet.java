package challenge.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import challenge.JsonWriter;
import challenge.app.Conduit.Services;
import challenge.app.Inputs.Body;
import challenge.app.Inputs.Invalid;
import challenge.app.Inputs.Params;
import challenge.pg.Query;
import challenge.pg.Stmt;
import challenge.servlet.Direct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
import static challenge.app.Op.UNFAVORITE;
import static challenge.app.Op.UPDATE_ARTICLE;

/**
 * {@code /api/articles}, the feed, one article, its favorite and its
 * comments.
 */
final class ArticlesServlet extends ApiServlet {
    private static final String[] WORDS = {"feed", "favorite", "comments"};
    private static final Ops GET = new Ops(WORDS)
            .on(2, null, ARTICLES)
            .on(3, null, ARTICLE)
            .on(3, "feed", FEED)
            .on(4, "comments", COMMENTS);
    private static final Ops POST = new Ops(WORDS)
            .on(2, null, CREATE_ARTICLE)
            .on(4, "favorite", FAVORITE)
            .on(4, "comments", CREATE_COMMENT);
    private static final Ops PUT = new Ops(WORDS).on(3, null, UPDATE_ARTICLE);
    private static final Ops DELETE = new Ops(WORDS)
            .on(3, null, DELETE_ARTICLE)
            .on(4, "favorite", UNFAVORITE)
            .on(5, "comments", DELETE_COMMENT);

    /** The list op by filter parameter index. */
    private static final Op[] FILTERED = {ARTICLES, null, ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED};

    ArticlesServlet(Services services) {
        super(services);
    }

    Ops table(String method) {
        return switch (method) {
            case "GET" -> GET;
            case "POST" -> POST;
            case "PUT" -> PUT;
            case "DELETE" -> DELETE;
            default -> null;
        };
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(GET, req, res);
        if (call == null) {
            return;
        }
        switch (call.op) {
            case ARTICLES, FEED -> list(call, res);
            case ARTICLE -> {
                call.target(call.start().int8(call.viewer));
                call.submit(ArticlesServlet::article);
            }
            default -> {
                call.target(call.start().int8(call.viewer));
                call.submit(ArticlesServlet::comments);
            }
        }
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(POST, req, res);
        if (call == null) {
            return;
        }
        switch (call.op) {
            case CREATE_ARTICLE -> create(call, res);
            case FAVORITE -> favorite(call);
            default -> comment(call, res);
        }
    }

    /** {@code PUT /api/articles/{slug}}: changes the given fields, author only. */
    protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(PUT, req, res);
        if (call == null) {
            return;
        }
        String title;
        String description;
        String text;
        try {
            Body body = Inputs.body(call.request, "article");
            title = Inputs.field(body, "title", false, true);
            description = Inputs.field(body, "description", false, true);
            text = Inputs.field(body, "body", false, true);
        } catch (Invalid e) {
            res.sendError(422, "invalid body");
            return;
        }
        call.target(call.start().int8(call.viewer))
            .text(title)
            .text(description)
            .text(text)
            .text(title == null ? null : Formats.slugify(title));
        call.submit(ArticlesServlet::updated);
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(DELETE, req, res);
        if (call == null) {
            return;
        }
        switch (call.op) {
            case DELETE_ARTICLE -> {
                call.target(call.start().int8(call.viewer));
                call.submit(ArticlesServlet::deleted);
            }
            case UNFAVORITE -> favorite(call);
            default -> {
                Direct r = call.request;
                long id;
                try {
                    id = Long.parseLong(new String(r.bytes(), r.segmentStart(4), r.segmentEnd(4) - r.segmentStart(4),
                            StandardCharsets.UTF_8));
                } catch (NumberFormatException e) {
                    res.sendError(404, "not found");
                    return;
                }
                call.target(call.start().int8(call.viewer)).int8(id);
                call.submit(ArticlesServlet::deleted);
            }
        }
    }

    /**
     * The article list or the feed, paged. A tag, author or favorited filter
     * (query parameter 2, 3 or 4) picks the list's statement.
     */
    private static void list(Call call, HttpServletResponse res) throws IOException {
        Params params = call.params;
        int limit;
        int offset;
        try {
            params.parse(call.request);
            limit = params.page(0, 20, 1, 100);
            offset = params.page(1, 0, 0, Integer.MAX_VALUE);
        } catch (Invalid | IllegalArgumentException e) {
            res.sendError(422, "invalid parameters");
            return;
        }
        int p = call.op == FEED
                ? 0
                : params.length(2) > 0
                        ? 2
                        : params.length(3) > 0
                                ? 3
                                : params.length(4) > 0 ? 4 : 0;
        call.op = call.op == FEED ? FEED : FILTERED[p];
        Query q = call.start()
                      .int8(call.viewer)
                      .int4(limit)
                      .int4(offset);
        if (p != 0) {
            q.text(params.bytes(), params.offset(p), params.length(p));
        }
        call.submit(ArticlesServlet::articles);
    }

    /** Inserts the tags, then the article. */
    private static void create(Call call, HttpServletResponse res) throws IOException {
        String title;
        String description;
        String text;
        String[] tags;
        try {
            Body body = Inputs.body(call.request, "article");
            title = Inputs.field(body, "title", true, true);
            description = Inputs.field(body, "description", true, true);
            text = Inputs.field(body, "body", true, true);
            tags = Inputs.stringArray(body, "tagList");
        } catch (Invalid e) {
            res.sendError(422, "invalid body");
            return;
        }
        tags = tags == null ? new String[0] : tags;
        call.start(Stmt.INSERT_TAGS, true).textArray(tags);
        call.query.int8(call.viewer)
            .text(title)
            .text(description)
            .text(text)
            .textArray(tags)
            .text(Formats.slugify(title));
        call.submit(ArticlesServlet::created);
    }

    /** Favorites or unfavorites, then reads the article back. */
    private static void favorite(Call call) {
        call.target(call.start(Stmt.ARTICLE, false).int8(call.viewer));
        call.target(call.query.int8(call.viewer));
        call.submit(ArticlesServlet::favorited);
    }

    private static void comment(Call call, HttpServletResponse res) throws IOException {
        String text;
        try {
            text = Inputs.field(Inputs.body(call.request, "comment"), "body", true, true);
        } catch (Invalid e) {
            res.sendError(422, "invalid body");
            return;
        }
        call.target(call.start().int8(call.viewer)).text(text);
        call.submit(ArticlesServlet::commented);
    }

    private static void articles(Call call, Query q) {
        Views.articles(call.json(), q);
        call.complete();
    }

    private static void article(Call call, Query q) {
        if (!call.missing(q, 404)) {
            write(call, q);
        }
    }

    private static void created(Call call, Query q) {
        if (!call.missing(q, 401)) {
            call.response.setStatus(201);
            write(call, q);
        }
    }

    private static void updated(Call call, Query q) {
        if (call.missing(q, 404)) {
            return;
        }
        if (q.isNull(0, 0)) {
            call.fail(403, "forbidden");
        } else {
            write(call, q);
        }
    }

    private static void favorited(Call call, Query q) {
        if (!call.missing(call.secondary, 404)) {
            write(call, call.secondary);
        }
    }

    private static void deleted(Call call, Query q) {
        if (call.missing(q, 404)) {
            return;
        }
        if (!q.boolAt(0, 0)) {
            call.fail(403, "forbidden");
        } else {
            call.response.setStatus(204);
            call.complete();
        }
    }

    private static void comments(Call call, Query q) {
        if (!call.missing(q, 404)) {
            Views.comments(call.json(), q);
            call.complete();
        }
    }

    private static void commented(Call call, Query q) {
        if (call.missing(q, 404)) {
            return;
        }
        call.response.setStatus(201);
        JsonWriter out = call.json();
        Views.raw(out, "{\"comment\":");
        Views.comment(out, q, 0, false);
        out.put('}');
        call.complete();
    }

    private static void write(Call call, Query q) {
        JsonWriter out = call.json();
        Views.raw(out, "{\"article\":");
        Views.article(out, q, 0, true);
        out.put('}');
        call.complete();
    }
}
