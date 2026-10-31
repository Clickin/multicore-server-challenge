package challenge.app;

import java.io.IOException;
import java.util.Arrays;

import challenge.WordTable;
import challenge.app.Conduit.Services;
import challenge.app.Op.Auth;
import challenge.servlet.Direct;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Base for the API servlets: the shared {@link Services}, {@link #begin} for
 * the request's op and pooled {@link Call}, and {@link Ops}, the path table
 * each servlet keeps per HTTP method. The servlets override {@code doGet},
 * {@code doPost}, ... themselves, so {@link HttpServlet} answers 405 and {@code
 * Allow} for the rest.
 */
abstract class ApiServlet extends HttpServlet {
    final Services services;

    ApiServlet(Services services) {
        this.services = services;
    }

    /**
     * The table for {@code method} (HEAD is GET's), or null when the servlet
     * does not handle it; for tests.
     */
    abstract Ops table(String method);

    /**
     * Looks up the request's op in {@code ops}, authenticates for it and returns
     * the exchange's pooled call reset for it; or sends 404 or 401 and returns
     * null.
     */
    Call begin(Ops ops, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Direct r = Direct.of(req);
        Op op = ops.find(r);
        if (op == null) {
            res.sendError(404, "not found");
            return null;
        }
        long viewer = 0;
        if (op.auth != Auth.NONE) {
            viewer = Inputs.viewer(r, r.local(services.jwt()), op.auth == Auth.REQUIRED);
            if (viewer < 0) {
                res.sendError(401, "unauthorized");
                return null;
            }
        }
        Call call = Call.of(r, services);
        call.reset(op, req, r, res, viewer);
        return call;
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
