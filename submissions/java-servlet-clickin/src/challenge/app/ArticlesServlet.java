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

    /** The list op by filter parameter index. */
    private static final Op[] FILTERED = {ARTICLES, null, ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED};

    ArticlesServlet(Services services) {
        super(
                services,
                new Ops(WORDS)
                        .on(2, null, ARTICLES)
                        .on(3, null, ARTICLE)
                        .on(3, "feed", FEED)
                        .on(4, "comments", COMMENTS),
                new Ops(WORDS)
                        .on(2, null, CREATE_ARTICLE)
                        .on(4, "favorite", FAVORITE)
                        .on(4, "comments", CREATE_COMMENT),
                new Ops(WORDS).on(3, null, UPDATE_ARTICLE),
                new Ops(WORDS)
                        .on(3, null, DELETE_ARTICLE)
                        .on(4, "favorite", UNFAVORITE)
                        .on(5, "comments", DELETE_COMMENT));
    }

    void handle(Op op, Direct req, Call call,
                HttpServletResponse res)
            throws IOException {
        if (op == ARTICLES || op == FEED) {
            list(op, req, call, res);
            return;
        }
        try {
            switch (op) {
                case CREATE_ARTICLE -> {
                    Body body = Inputs.body(req, "article");
                    String title = Inputs.field(body, "title", true, true);
                    String description = Inputs.field(body, "description", true, true);
                    String text = Inputs.field(body, "body", true, true);
                    String[] tags = Inputs.stringArray(body, "tagList");
                    tags = tags == null ? new String[0] : tags;
                    call.start(Stmt.INSERT_TAGS, true).textArray(tags);
                    call.query.int8(call.viewer)
                        .text(title)
                        .text(description)
                        .text(text)
                        .textArray(tags)
                        .text(Formats.slugify(title));
                }
                case UPDATE_ARTICLE -> {
                    Body body = Inputs.body(req, "article");
                    String title = Inputs.field(body, "title", false, true);
                    String description = Inputs.field(body, "description", false, true);
                    String text = Inputs.field(body, "body", false, true);
                    call.target(call.start().int8(call.viewer))
                        .text(title)
                        .text(description)
                        .text(text)
                        .text(title == null ? null : Formats.slugify(title));
                }
                case FAVORITE, UNFAVORITE -> {
                    call.target(call.start(Stmt.ARTICLE, false).int8(call.viewer));
                    call.target(call.query.int8(call.viewer));
                }
                case CREATE_COMMENT -> {
                    String text = Inputs.field(Inputs.body(req, "comment"), "body", true, true);
                    call.target(call.start().int8(call.viewer)).text(text);
                }
                case DELETE_COMMENT -> {
                    long id;
                    try {
                        id = Long.parseLong(new String(req.bytes(), req.segmentStart(4), req.segmentEnd(4) - req.segmentStart(4),
                                StandardCharsets.UTF_8));
                    } catch (NumberFormatException e) {
                        res.sendError(404, "not found");
                        return;
                    }
                    call.target(call.start().int8(call.viewer)).int8(id);
                }
                default -> call.target(call.start().int8(call.viewer));
            }
        } catch (Invalid e) {
            res.sendError(422, "invalid body");
            return;
        }
        call.submit();
    }

    /**
     * The article list or the feed, paged. A tag, author or favorited filter
     * (query parameter 2, 3 or 4) picks the list's statement.
     */
    private static void list(Op op, Direct req, Call call,
            HttpServletResponse res)
            throws IOException {
        Params params = call.params;
        int limit;
        int offset;
        try {
            params.parse(req);
            limit = params.page(0, 20, 1, 100);
            offset = params.page(1, 0, 0, Integer.MAX_VALUE);
        } catch (Invalid | IllegalArgumentException e) {
            res.sendError(422, "invalid parameters");
            return;
        }
        int p = op == FEED
                ? 0
                : params.length(2) > 0
                        ? 2
                        : params.length(3) > 0
                                ? 3
                                : params.length(4) > 0 ? 4 : 0;
        call.op = op == FEED ? FEED : FILTERED[p];
        Query q = call.start()
                      .int8(call.viewer)
                      .int4(limit)
                      .int4(offset);
        if (p != 0) {
            q.text(params.bytes(), params.offset(p), params.length(p));
        }
        call.submit();
    }

    void finish(Op op, Call call, Query q) {
        switch (op) {
            case ARTICLES, ARTICLES_TAG, ARTICLES_AUTHOR, ARTICLES_FAVORITED, FEED -> {
                Views.articles(call.json(), q);
                call.complete();
            }
            case ARTICLE, CREATE_ARTICLE -> {
                if (!call.missing(q, op == ARTICLE ? 404 : 401)) {
                    write(op, call, q);
                }
            }
            case UPDATE_ARTICLE -> {
                if (call.missing(q, 404)) {
                    return;
                }
                if (q.isNull(0, 0)) {
                    call.fail(403, "forbidden");
                } else {
                    write(op, call, q);
                }
            }
            case FAVORITE, UNFAVORITE -> {
                if (!call.missing(call.secondary, 404)) {
                    write(op, call, call.secondary);
                }
            }
            case DELETE_ARTICLE, DELETE_COMMENT -> {
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
            case COMMENTS -> {
                if (!call.missing(q, 404)) {
                    Views.comments(call.json(), q);
                    call.complete();
                }
            }
            case CREATE_COMMENT -> {
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
            default -> throw new AssertionError(op);
        }
    }

    private static void write(Op op, Call call, Query q) {
        if (op == CREATE_ARTICLE) {
            call.response.setStatus(201);
        }
        JsonWriter out = call.json();
        Views.raw(out, "{\"article\":");
        Views.article(out, q, 0, true);
        out.put('}');
        call.complete();
    }
}
