package challenge.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import challenge.BcryptPool;
import challenge.Jwt;
import challenge.app.ApiServlet.Ops;
import challenge.app.Inputs.Body;
import challenge.app.Inputs.Invalid;
import challenge.pg.PgDispatcher;
import challenge.servlet.Container;
import challenge.servlet.Direct;
import challenge.servlet.Direct.Local;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
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
 * The Conduit (RealWorld) API as servlets: one per resource, mapped the way a
 * web.xml would map them.
 */
public final class Conduit {
    private Conduit() {}

    /**
     * What the servlets share: the Postgres dispatcher, the bcrypt pool, the
     * JWT secret, and the per-loop {@link Jwt} (its Mac and buffers are not
     * thread-safe, and too big to keep per request).
     */
    public record Services(PgDispatcher dispatcher, BcryptPool bcrypt, String secret,
                           Local<Jwt> jwt) {
        public Services(PgDispatcher dispatcher, BcryptPool bcrypt, String secret) {
            this(dispatcher, bcrypt, secret, new Local<>(() -> {
                try {
                    return new Jwt(secret);
                } catch (GeneralSecurityException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
    }

    public static Container container(Services services) throws ServletException {
        Container c = new Container();
        UsersServlet users = new UsersServlet(services);
        c.addServlet("/api/tags", new TagsServlet(services));
        c.addServlet("/api/users/*", users);
        c.addServlet("/api/user", users);
        c.addServlet("/api/profiles/*", new ProfilesServlet(services));
        c.addServlet("/api/articles/*", new ArticlesServlet(services));
        c.addServlet("/__async", new AsyncProbeServlet());
        c.addServlet("/__echo", new EchoServlet());
        c.setErrorServlet(new ErrorServlet());
        return c;
    }

    /** {@code GET /api/tags}. */
    static final class TagsServlet extends ApiServlet {
        TagsServlet(Services services) {
            super(services, new Ops().on(2, null, TAGS),
                    null, null, null);
        }

        void handle(Op op, Direct req, Call call,
                    HttpServletResponse res) {
            call.start();
        }
    }

    /** {@code /api/users}, {@code /api/users/login} and {@code /api/user}. */
    static final class UsersServlet extends ApiServlet {
        private static final String[] WORDS = {"user", "users", "login"};

        UsersServlet(Services services) {
            super(
                    services,
                    new Ops(WORDS).on(2, "user", USER),
                    new Ops(WORDS)
                            .on(2, "users", REGISTER)
                            .on(3, "login", LOGIN),
                    new Ops(WORDS).on(2, "user", UPDATE),
                    null);
        }

        void handle(Op op, Direct req, Call call,
                    HttpServletResponse res)
                throws IOException {
            if (op != USER) {
                try {
                    Body body = Inputs.body(req, "user");
                    if (op != LOGIN) {
                        call.username = Inputs.field(body, "username", op == REGISTER, true);
                    }
                    call.email = Inputs.field(body, "email", op != UPDATE, true);
                    call.password = Inputs.password(body, op != UPDATE);
                    if (op == UPDATE) {
                        call.bio = Inputs.field(body, "bio", false, false);
                        call.image = Inputs.field(body, "image", false, false);
                    }
                } catch (Invalid e) {
                    res.sendError(422, "invalid body");
                    return;
                }
            }
            call.start();
        }
    }

    /** {@code /api/profiles/{username}} and its {@code /follow}. */
    static final class ProfilesServlet extends ApiServlet {
        ProfilesServlet(Services services) {
            super(
                    services,
                    new Ops("follow").on(3, null, PROFILE),
                    new Ops("follow").on(4, "follow", FOLLOW),
                    null,
                    new Ops("follow").on(4, "follow", UNFOLLOW));
        }

        void handle(Op op, Direct req, Call call,
                    HttpServletResponse res) {
            call.start();
        }
    }

    /**
     * {@code /api/articles}, the feed, one article, its favorite and its
     * comments.
     */
    static final class ArticlesServlet extends ApiServlet {
        private static final String[] WORDS = {"feed", "favorite", "comments"};

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
                try {
                    call.params.parse(req);
                    call.limit = call.params.page(0, 20, 1, 100);
                    call.offset = call.params.page(1, 0, 0, Integer.MAX_VALUE);
                } catch (Invalid | IllegalArgumentException e) {
                    res.sendError(422, "invalid parameters");
                    return;
                }
                if (op == ARTICLES) {
                    call.op = call.params.length(2) > 0
                            ? ARTICLES_TAG
                            : call.params.length(3) > 0
                                    ? ARTICLES_AUTHOR
                                    : call.params.length(4) > 0 ? ARTICLES_FAVORITED : ARTICLES;
                }
            }
            try {
                if (op == CREATE_ARTICLE || op == UPDATE_ARTICLE) {
                    Body body = Inputs.body(req, "article");
                    boolean required = op == CREATE_ARTICLE;
                    call.title = Inputs.field(body, "title", required, true);
                    call.description = Inputs.field(body, "description", required, true);
                    call.bodyText = Inputs.field(body, "body", required, true);
                    if (required) {
                        String[] tags = Inputs.stringArray(body, "tagList");
                        call.tags = tags == null ? new String[0] : tags;
                    }
                } else if (op == CREATE_COMMENT) {
                    call.bodyText = Inputs.field(Inputs.body(req, "comment"), "body", true, true);
                }
            } catch (Invalid e) {
                res.sendError(422, "invalid body");
                return;
            }
            if (op == DELETE_COMMENT) {
                try {
                    call.commentId = Long.parseLong(new String(req.bytes(), req.segmentStart(4), req.segmentEnd(4) - req.segmentStart(4),
                            StandardCharsets.UTF_8));
                } catch (NumberFormatException e) {
                    res.sendError(404, "not found");
                    return;
                }
            }
            call.start();
        }
    }

    /**
     * {@code GET /__async}: completes an async request from {@code
     * AsyncContext.start}, or with {@code ?dispatch} re-dispatches it and
     * answers from the ASYNC dispatch (used by the HTTP smoke test).
     */
    static final class AsyncProbeServlet extends HttpServlet {
        protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            if (req.getDispatcherType() == DispatcherType.ASYNC) {
                res.setContentType("text/plain");
                res.getWriter().print("dispatched");
                return;
            }
            AsyncContext async = req.startAsync();
            if (req.getQueryString() != null && req.getQueryString().equals("dispatch")) {
                async.start(async::dispatch);
            } else {
                async.start(async::complete);
            }
        }
    }

    /**
     * {@code POST /__echo}: writes the request body back, and the peer address
     * and local port as {@code X-Remote}.
     */
    static final class EchoServlet extends HttpServlet {
        protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
            res.setHeader("X-Remote", req.getRemoteAddr() + " " + req.getLocalPort());
            req.getInputStream().transferTo(res.getOutputStream());
        }
    }

    /** Renders every {@code sendError} as {@code {"errors":{"body":[message]}}}. */
    static final class ErrorServlet extends HttpServlet {
        protected void service(HttpServletRequest req, HttpServletResponse res) {
            Object message = req.getAttribute(RequestDispatcher.ERROR_MESSAGE);
            res.setContentType("application/json");
            Views.errors(
                    Direct.of(req).output(),
                    message != null
                            ? message.toString()
                            : res.getStatus() == 404 ? "not found" : "error");
        }
    }
}
