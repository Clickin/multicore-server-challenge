package challenge.app;

import java.nio.charset.StandardCharsets;

import challenge.app.ConduitServlet.Route;
import challenge.servlet.Request;

import static challenge.app.ConduitServlet.Route.ARTICLE;
import static challenge.app.ConduitServlet.Route.ARTICLES;
import static challenge.app.ConduitServlet.Route.COMMENTS;
import static challenge.app.ConduitServlet.Route.CREATE_ARTICLE;
import static challenge.app.ConduitServlet.Route.CREATE_COMMENT;
import static challenge.app.ConduitServlet.Route.DELETE_ARTICLE;
import static challenge.app.ConduitServlet.Route.DELETE_COMMENT;
import static challenge.app.ConduitServlet.Route.FAVORITE;
import static challenge.app.ConduitServlet.Route.FEED;
import static challenge.app.ConduitServlet.Route.FOLLOW;
import static challenge.app.ConduitServlet.Route.LOGIN;
import static challenge.app.ConduitServlet.Route.PROFILE;
import static challenge.app.ConduitServlet.Route.REGISTER;
import static challenge.app.ConduitServlet.Route.TAGS;
import static challenge.app.ConduitServlet.Route.UNFAVORITE;
import static challenge.app.ConduitServlet.Route.UNFOLLOW;
import static challenge.app.ConduitServlet.Route.UPDATE;
import static challenge.app.ConduitServlet.Route.UPDATE_ARTICLE;
import static challenge.app.ConduitServlet.Route.USER;

public final class RouteTest {
    private static Route route(String method, String path) {
        byte[] bytes = (method + " " + path + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        Request r = new Request(null);
        r.data = bytes;
        r.pathStart = method.length() + 1;
        r.pathEnd = r.pathStart + path.length();
        int count = ConduitServlet.segments(r, r.segmentStarts, r.segmentEnds);
        return ConduitServlet.route(r, r.segmentStarts, r.segmentEnds, count, ConduitServlet.verb(r));
    }

    private static void check(String method, String path, Route expected) {
        Route actual = route(method, path);
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

    public static void main(String[] args) {
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
        // Near misses: prefixes, extensions, case, wrong verb, wrong count, wrong root.
        check("GET", "/api/tag", null);
        check("GET", "/api/tagsx", null);
        check("GET", "/api/Tags", null);
        check("GET", "/api/articlesx", null);
        check("GET", "/api/feed", null);
        check("GET", "/api/users", null);
        check("POST", "/api/users/logins", null);
        check("GET", "/api/users/login", null);
        check("POST", "/api/tags", null);
        check("PATCH", "/api/user", null);
        check("GETX", "/api/tags", null);
        check("GE", "/api/tags", null);
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
        System.out.println("challenge.app.RouteTest passed");
    }
}
