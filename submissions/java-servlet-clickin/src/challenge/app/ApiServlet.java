package challenge.app;

import java.io.IOException;
import java.util.Arrays;

import challenge.WordTable;
import challenge.app.Conduit.Services;
import challenge.app.Op.Auth;
import challenge.pg.Query;
import challenge.servlet.Direct;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Base for the API servlets: picks the {@link Op} for the request from a
 * per-method {@link Ops} table, authenticates, and hands the servlet a pooled
 * {@link Call}. The servlet binds and submits the op's work in {@link #handle}
 * and writes the response from the result in {@link #finish}.
 */
abstract class ApiServlet extends HttpServlet {
    final Services services;
    private final Ops get;
    private final Ops post;
    private final Ops put;
    private final Ops delete;

    /** A null table leaves that method to {@link HttpServlet}, which answers 405. */
    ApiServlet(Services services, Ops get, Ops post,
               Ops put, Ops delete) {
        this.services = services;
        this.get = get;
        this.post = post;
        this.put = put;
        this.delete = delete;
    }

    /**
     * Reads the op's inputs, binds them to the query from {@link Call#start} and
     * submits; or sends an error response.
     */
    abstract void handle(Op op, Direct req, Call call,
                         HttpServletResponse res)
            throws IOException;

    /**
     * On the request's loop once the submitted work succeeded: submits the next
     * step, or writes the response and completes the async request. {@code q} is
     * the op's own query.
     */
    abstract void finish(Op op, Call call, Query q);

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        if (get == null) {
            super.doGet(req, res);
        } else {
            run(get, req, res);
        }
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        if (post == null) {
            super.doPost(req, res);
        } else {
            run(post, req, res);
        }
    }

    protected void doPut(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        if (put == null) {
            super.doPut(req, res);
        } else {
            run(put, req, res);
        }
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        if (delete == null) {
            super.doDelete(req, res);
        } else {
            run(delete, req, res);
        }
    }

    /**
     * Allow lists the methods that have a table (HttpServlet's reflection would
     * list all four).
     */
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) {
        StringBuilder allow = new StringBuilder(get == null ? "" : "GET, HEAD, ");
        allow.append(post == null ? "" : "POST, ")
             .append(put == null ? "" : "PUT, ")
             .append(delete == null ? "" : "DELETE, ")
             .append("OPTIONS, TRACE");
        res.setHeader("Allow", allow.toString());
    }

    /** The op for the request's method and path, or null; for tests. */
    Op op(HttpServletRequest req) {
        Ops ops = switch (req.getMethod()) {
            case "GET", "HEAD" -> get;
            case "POST" -> post;
            case "PUT" -> put;
            case "DELETE" -> delete;
            default -> null;
        };
        return ops == null ? null : ops.find(Direct.of(req));
    }

    /** True when the method has a table; for tests. */
    boolean allows(String method) {
        return switch (method) {
            case "GET", "HEAD" -> get != null;
            case "POST" -> post != null;
            case "PUT" -> put != null;
            case "DELETE" -> delete != null;
            default -> false;
        };
    }

    private void run(Ops ops, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Direct req = Direct.of(request);
        Op op = ops.find(req);
        if (op == null) {
            response.sendError(404, "not found");
            return;
        }
        Call call = Call.of(req, services);
        long viewer = 0;
        if (op.auth != Auth.NONE) {
            viewer = Inputs.viewer(req, req.local(services.jwt()), op.auth == Auth.REQUIRED);
            if (viewer < 0) {
                response.sendError(401, "unauthorized");
                return;
            }
        }
        call.reset(this, op, request, req, response, viewer);
        handle(op, req, call, response);
    }

    /**
     * Op by (path segment count, keyword in the last fixed segment), one table
     * per HTTP method. A lookup is a word-table probe and an array load; a null
     * keyword matches any segment (a slug or username).
     */
    static final class Ops {
        private static final int COUNTS = 7;

        /**
         * Segment that carries the keyword for each segment count: /api/{1},
         * /api/x/{2}, /api/x/y/{3}, /api/x/y/{3}/id.
         */
        private static final int[] KEY_SEGMENT = {0, 0, 1, 2, 3, 3,
                3};

        private final WordTable words;
        private final String[] list;
        private final Op[] table;

        Ops(String... keywords) {
            list = keywords;
            words = new WordTable(keywords);
            table = new Op[COUNTS * (keywords.length + 1)];
        }

        Ops on(int count, String keyword, Op op) {
            int width = list.length + 1;
            for (int k = 0; k < width; k++) {
                if (keyword == null || k > 0 && list[k - 1].equals(keyword)) {
                    table[count * width + k] = op;
                }
            }
            if (keyword != null && !Arrays.asList(list).contains(keyword)) {
                throw new IllegalArgumentException("undeclared keyword: " + keyword);
            }
            return this;
        }

        Op find(Direct r) {
            int c = Math.max(0,
                    Math.min(r.segmentCount(), COUNTS - 1));
            int s = KEY_SEGMENT[c];
            return table[c * (list.length + 1) + words.id(r.bytes(), r.segmentStart(s), r.segmentEnd(s))];
        }
    }
}
