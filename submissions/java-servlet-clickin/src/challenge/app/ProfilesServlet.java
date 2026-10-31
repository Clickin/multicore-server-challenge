package challenge.app;

import java.io.IOException;

import challenge.JsonWriter;
import challenge.app.Conduit.Services;
import challenge.pg.Query;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.Op.FOLLOW;
import static challenge.app.Op.PROFILE;
import static challenge.app.Op.UNFOLLOW;

/** {@code /api/profiles/{username}} and its {@code /follow}. */
final class ProfilesServlet extends ApiServlet {
    private static final Ops GET = new Ops("follow").on(3, null, PROFILE);
    private static final Ops POST = new Ops("follow").on(4, "follow", FOLLOW);
    private static final Ops DELETE = new Ops("follow").on(4, "follow", UNFOLLOW);

    ProfilesServlet(Services services) {
        super(services);
    }

    Ops table(String method) {
        return switch (method) {
            case "GET" -> GET;
            case "POST" -> POST;
            case "DELETE" -> DELETE;
            default -> null;
        };
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        profile(GET, req, res);
    }

    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        profile(POST, req, res);
    }

    protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
        profile(DELETE, req, res);
    }

    /** Reads the profile, or follows or unfollows it and returns it. */
    private void profile(Ops ops, HttpServletRequest req, HttpServletResponse res) throws IOException {
        Call call = begin(ops, req, res);
        if (call == null) {
            return;
        }
        call.target(call.start().int8(call.viewer));
        call.submit(ProfilesServlet::write);
    }

    private static void write(Call call, Query q) {
        if (call.missing(q, 404)) {
            return;
        }
        JsonWriter out = call.json();
        Views.raw(out, "{\"profile\":");
        Views.profile(out, q, 0, 0,
                call.op == PROFILE ? q.boolAt(0, 3) : call.op == FOLLOW);
        out.put('}');
        call.complete();
    }
}
