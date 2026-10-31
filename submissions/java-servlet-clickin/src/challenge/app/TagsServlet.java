package challenge.app;

import challenge.app.Conduit.Services;
import challenge.pg.Query;
import challenge.servlet.Direct;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.Op.TAGS;

/** {@code GET /api/tags}. */
final class TagsServlet extends ApiServlet {
    TagsServlet(Services services) {
        super(services, new Ops().on(2, null, TAGS),
                null, null, null);
    }

    void handle(Op op, Direct req, Call call,
                HttpServletResponse res) {
        call.start();
        call.submit();
    }

    void finish(Op op, Call call, Query q) {
        Views.tags(call.json(), q);
        call.complete();
    }
}
