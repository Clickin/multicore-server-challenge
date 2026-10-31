package challenge.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import challenge.JsonWriter;
import challenge.app.Conduit.Services;
import challenge.app.Inputs.Body;
import challenge.app.Inputs.Invalid;
import challenge.pg.Query;
import challenge.servlet.Direct;
import jakarta.servlet.http.HttpServletResponse;

import static challenge.app.Op.LOGIN;
import static challenge.app.Op.REGISTER;
import static challenge.app.Op.UPDATE;
import static challenge.app.Op.USER;

/**
 * {@code /api/users}, {@code /api/users/login} and {@code /api/user}. Register
 * and a password update hash before the database; login checks the password
 * against the stored hash after it.
 */
final class UsersServlet extends ApiServlet {
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
        if (op == USER) {
            call.start().int8(call.viewer);
            call.submit();
            return;
        }
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
        Query q = call.start();
        if (op == LOGIN) {
            q.text(call.email);
            call.submit();
        } else if (call.password != null) {
            call.bcrypt(call.password);
        } else {
            update(call, null);
        }
    }

    void finish(Op op, Call call, Query q) {
        if (call.hashing) {
            if (op == REGISTER) {
                q.text(call.username)
                 .text(call.email)
                 .text(call.unit.bcryptHash);
                call.submit();
            } else if (op == UPDATE) {
                update(call, call.unit.bcryptHash);
            } else if (call.unit.bcryptMatches) {
                write(op, call, q);
            } else {
                call.fail(401, "invalid credentials");
            }
            return;
        }
        if (call.missing(q, 401)) {
            return;
        }
        if (op == LOGIN) {
            call.unit.bcryptHash = new String(q.bytes(), q.offset(0, 5), q.length(0, 5),
                    StandardCharsets.UTF_8);
            call.bcrypt(call.password);
        } else {
            write(op, call, q);
        }
    }

    private static void update(Call call, String hash) {
        call.query.int8(call.viewer)
            .text(call.email)
            .text(call.username)
            .text(hash)
            .text(call.bio)
            .text(call.image);
        call.submit();
    }

    /**
     * The user with a fresh token for register and login, or the request's own
     * token.
     */
    private static void write(Op op, Call call, Query q) {
        byte[] token = call.request.bytes();
        int at = call.tokenOffset;
        int len = call.tokenLength;
        if (op == REGISTER || op == LOGIN) {
            at = 0;
            len = call.request.local(call.services.jwt()).sign(q.int8At(0, 0), System.currentTimeMillis() / 1000, call.signed, 0);
            token = call.signed;
        }
        if (op == REGISTER) {
            call.response.setStatus(201);
        }
        JsonWriter out = call.json();
        Views.raw(out, "{\"user\":");
        Views.user(out, q, 0, token, at, len);
        out.put('}');
        call.complete();
    }
}
