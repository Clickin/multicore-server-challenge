package challenge.app;

import java.io.IOException;

import challenge.app.Conduit.Services;
import challenge.pg.Query;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.Op.TAGS;

/** {@code GET /api/tags}. */
final class TagsServlet extends ApiServlet {
    private static final Ops GET = new Ops().on(2, null, TAGS);

    TagsServlet(Services services) {
        super(services);
    }

    Ops table(String method) {
        return method.equals("GET") ? GET : null;
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(GET, req, res);
        if (call == null) {
            return;
        }
        call.start();
        call.submit(TagsServlet::write);
    }

    private static void write(Call call, Query q) {
        Views.tags(call.json(), q);
        call.complete();
    }
}
