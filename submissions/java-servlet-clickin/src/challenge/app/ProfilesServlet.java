package challenge.app;

import challenge.JsonWriter;
import challenge.app.Conduit.Services;
import challenge.pg.Query;
import challenge.servlet.Direct;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.Op.FOLLOW;
import static challenge.app.Op.PROFILE;
import static challenge.app.Op.UNFOLLOW;

/** {@code /api/profiles/{username}} and its {@code /follow}. */
final class ProfilesServlet extends ApiServlet {
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
        call.target(call.start().int8(call.viewer));
        call.submit();
    }

    void finish(Op op, Call call, Query q) {
        if (call.missing(q, 404)) {
            return;
        }
        JsonWriter out = call.json();
        Views.raw(out, "{\"profile\":");
        Views.profile(out, q, 0, 0,
                op == PROFILE ? q.boolAt(0, 3) : op == FOLLOW);
        out.put('}');
        call.complete();
    }
}
