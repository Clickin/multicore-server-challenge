package challenge.app;

import java.nio.charset.StandardCharsets;

import challenge.app.Conduit.Services;
import challenge.servlet.Container;
import challenge.servlet.Request;
import jakarta.servlet.http.HttpServlet;

import static challenge.app.Op.ARTICLE;
import static challenge.app.Op.ARTICLES;
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

/** Container mapping plus each API servlet's per-method op table. */
public final class RouteTest {
    private static final String NOT_ALLOWED = "405";
    private static final Container CONTAINER;

    static {
        try {
            CONTAINER = Conduit.container(new Services(null, null, "secret"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The op, "405" when the mapped servlet has no table for the method, or
     * null for 404.
     */
    private static Object route(String method, String path) {
        byte[] bytes = (method + " " + path + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        Request r = new Request(null);
        r.data = bytes;
        r.pathStart = method.length() + 1;
        r.pathEnd = r.pathStart + path.length();
        HttpServlet servlet = CONTAINER.map(r);
        if (!(servlet instanceof ApiServlet api)) {
            return null;
        }
        return api.allows(r.getMethod()) ? api.op(r) : NOT_ALLOWED;
    }

    private static void check(String method, String path, Object expected) {
        Object actual = route(method, path);
        if (actual != expected) {
            throw new AssertionError(method
                    + " "
                    + path
                    + ": expected "
                    + expected
                    + ", got "
                    + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        check("GET", "/api/tags", TAGS);
        check("POST", "/api/users", REGISTER);
        check("POST", "/api/users/login", LOGIN);
        check("GET", "/api/user", USER);
        check("PUT", "/api/user", UPDATE);
        check("GET", "/api/profiles/jake", PROFILE);
        check("POST", "/api/profiles/jake/follow", FOLLOW);
        check("DELETE", "/api/profiles/jake/follow", UNFOLLOW);
        check("GET", "/api/articles", ARTICLES);
        check("POST", "/api/articles", CREATE_ARTICLE);
        check("GET", "/api/articles/feed", FEED);
        check("GET", "/api/articles/how-to", ARTICLE);
        check("PUT", "/api/articles/how-to", UPDATE_ARTICLE);
        check("DELETE", "/api/articles/how-to", DELETE_ARTICLE);
        check("POST", "/api/articles/how-to/favorite", FAVORITE);
        check("DELETE", "/api/articles/how-to/favorite", UNFAVORITE);
        check("GET", "/api/articles/how-to/comments", COMMENTS);
        check("POST", "/api/articles/how-to/comments", CREATE_COMMENT);
        check("DELETE", "/api/articles/how-to/comments/7", DELETE_COMMENT);
        // Slugs and usernames that collide with keywords are still slugs.
        check("GET", "/api/articles/login", ARTICLE);
        check("PUT", "/api/articles/feed", UPDATE_ARTICLE);
        check("GET", "/api/articles/comments", ARTICLE);
        check("GET", "/api/profiles/follow", PROFILE);
        check("GET", "/api/profiles/articles", PROFILE);
        check("POST", "/api/profiles/feed/follow", FOLLOW);
        check("GET", "/api/articles/favorite/comments", COMMENTS);
        check("DELETE", "/api/articles/x/comments/comments", DELETE_COMMENT);
        // Near misses: prefixes, extensions, case, wrong count, wrong root (404).
        // A method the servlet has no table for is HttpServlet's 405 (501 for unknown methods) instead of 404.
        check("GET", "/api/tag", null);
        check("GET", "/api/tagsx", null);
        check("GET", "/api/Tags", null);
        check("GET", "/api/articlesx", null);
        check("GET", "/api/feed", null);
        check("GET", "/api/users", null);
        check("POST", "/api/users/logins", null);
        check("GET", "/api/users/login", null);
        check("POST", "/api/tags", NOT_ALLOWED);
        check("PATCH", "/api/user", NOT_ALLOWED);
        check("GETX", "/api/tags", NOT_ALLOWED);
        check("GE", "/api/tags", NOT_ALLOWED);
        check("GET", "/apix/tags", null);
        check("GET", "/ap/tags", null);
        check("GET", "/x/api/tags", null);
        check("GET", "/api", null);
        check("GET", "/api/tags/x", null);
        check("DELETE", "/api/articles/x/comments", null);
        check("GET", "/api/articles/x/comments/7", null);
        check("POST", "/api/profiles/jake/unfollow", null);
        check("POST", "/api/articles/x/favorites", null);
        check("DELETE", "/api/articles/x/y/comments", null);
        check("GET", "/api/articles/a/b/c/d", null);
        check("GET", "/api/articles//comments", null);
        check("DELETE", "/api/user", NOT_ALLOWED);
        check("DELETE", "/api/tags", NOT_ALLOWED);
        check("PUT", "/api/profiles/jake", NOT_ALLOWED);
        // Method present, path shape unknown to it: still 404.
        check("POST", "/api/user", null);
        check("PUT", "/api/users", null);
        check("HEAD", "/api/tags", TAGS);
        check("HEAD", "/api/articles/how-to", ARTICLE);
        System.out.println("challenge.app.RouteTest passed");
    }
}
