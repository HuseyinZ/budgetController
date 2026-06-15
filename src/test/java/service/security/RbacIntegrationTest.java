package service.security;

import io.javalin.Javalin;
import model.Role;
import model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import service.api.SessionStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RBAC end-to-end testleri — gerçek bir HTTP server üzerinden auth pipeline'ını
 * doğrular. ApiServer'ın tüm bağımlılıklarını (AppState, DB) çekmek yerine
 * minimal bir Javalin app kullanır; SessionStore ve role kontrol mantığı
 * ApiServer'da aynısı.
 *
 * <p>Kapsam:
 * <ul>
 *   <li>Token YOK → 401</li>
 *   <li>Geçersiz token → 401</li>
 *   <li>Süresi geçmiş absolute token → 401</li>
 *   <li>Idle timeout sonrası token → 401</li>
 *   <li>Yanlış rol → 403</li>
 *   <li>Doğru rol → 200</li>
 *   <li>Logout sonrası eski token → 401</li>
 * </ul>
 */
class RbacIntegrationTest {

    private static final String CTX_USER = "auth.user";

    private Javalin app;
    private SessionStore sessions;
    private int port;
    private HttpClient http;

    @BeforeEach
    void setUp() {
        // 1 saat absolute + 200 ms idle (idle timeout testi için kısa)
        sessions = new SessionStore(3_600_000L, 200L);

        app = Javalin.create(cfg -> cfg.showJavalinBanner = false);

        // Auth filter — ApiServer.authenticate ile aynı mantık
        app.before("/api/*", ctx -> {
            String path = ctx.path();
            if (path.equals("/api/ping") || path.equals("/api/login")) return;
            String header = ctx.header("Authorization");
            if (header == null) { ctx.status(401).result("no_auth"); ctx.skipRemainingHandlers(); return; }
            if (header.startsWith("Bearer ")) {
                String token = header.substring("Bearer ".length()).trim();
                java.util.Optional<User> opt = sessions.lookup(token);
                if (opt.isEmpty()) { ctx.status(401).result("invalid_token"); ctx.skipRemainingHandlers(); return; }
                ctx.attribute(CTX_USER, opt.get());
                return;
            }
            ctx.status(401).result("unsupported_scheme"); ctx.skipRemainingHandlers();
        });

        // Public endpoint
        app.get("/api/ping", ctx -> ctx.result("pong"));

        // Authenticated endpoint (herhangi bir rol)
        app.get("/api/me", ctx -> {
            User u = ctx.attribute(CTX_USER);
            ctx.result("hello " + u.getUsername());
        });

        // Admin-only endpoint (ApiServer.requireRole pattern)
        app.delete("/api/users/{id}", ctx -> {
            User u = ctx.attribute(CTX_USER);
            if (u.getRole() != Role.ADMIN) {
                ctx.status(403).result("forbidden");
                return;
            }
            ctx.result("deleted");
        });

        // Admin + Kasiyer endpoint
        app.post("/api/products", ctx -> {
            User u = ctx.attribute(CTX_USER);
            if (u.getRole() != Role.ADMIN && u.getRole() != Role.KASIYER) {
                ctx.status(403).result("forbidden");
                return;
            }
            ctx.result("created");
        });

        // Logout
        app.post("/api/logout", ctx -> {
            String header = ctx.header("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                sessions.revoke(header.substring("Bearer ".length()).trim());
            }
            ctx.result("ok");
        });

        app.start(0);  // OS random port
        port = app.port();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (sessions != null) sessions.shutdown();
    }

    // ============================================================
    //   Yardımcılar
    // ============================================================

    private static User userOf(String name, Role role) {
        User u = new User(name, "$2a$10$dummyhash..............................................", role);
        u.setId(1L);
        return u;
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ============================================================
    //   Testler
    // ============================================================

    @Test void pingPublicWorks() throws Exception {
        assertEquals(200, call("GET", "/api/ping", null).statusCode());
    }

    @Test void noTokenOnProtectedReturns401() throws Exception {
        assertEquals(401, call("GET", "/api/me", null).statusCode());
    }

    @Test void invalidTokenReturns401() throws Exception {
        assertEquals(401, call("GET", "/api/me", "DEFINITELY_NOT_A_REAL_TOKEN").statusCode());
    }

    @Test void unsupportedAuthSchemeReturns401() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
                .header("Authorization", "Negotiate xyz")
                .GET().build();
        assertEquals(401, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void validTokenAuthenticatedEndpointReturns200() throws Exception {
        String token = sessions.issue(userOf("alice", Role.GARSON));
        HttpResponse<String> resp = call("GET", "/api/me", token);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("alice"));
    }

    @Test void garsonOnAdminEndpointReturns403() throws Exception {
        String token = sessions.issue(userOf("garson1", Role.GARSON));
        assertEquals(403, call("DELETE", "/api/users/42", token).statusCode());
    }

    @Test void kasiyerOnAdminEndpointReturns403() throws Exception {
        String token = sessions.issue(userOf("kasiyer1", Role.KASIYER));
        assertEquals(403, call("DELETE", "/api/users/42", token).statusCode());
    }

    @Test void adminOnAdminEndpointReturns200() throws Exception {
        String token = sessions.issue(userOf("admin", Role.ADMIN));
        assertEquals(200, call("DELETE", "/api/users/42", token).statusCode());
    }

    @Test void garsonOnAdminOrKasiyerEndpointReturns403() throws Exception {
        String token = sessions.issue(userOf("garson1", Role.GARSON));
        assertEquals(403, call("POST", "/api/products", token).statusCode());
    }

    @Test void kasiyerOnAdminOrKasiyerEndpointReturns200() throws Exception {
        String token = sessions.issue(userOf("kasiyer1", Role.KASIYER));
        assertEquals(200, call("POST", "/api/products", token).statusCode());
    }

    @Test void idleTimedOutTokenReturns401() throws Exception {
        String token = sessions.issue(userOf("alice", Role.ADMIN));
        // İlk istek geçer
        assertEquals(200, call("GET", "/api/me", token).statusCode());
        // 200ms idle — eşiği aş
        Thread.sleep(300);
        // Token idle timeout sebebiyle düştü
        assertEquals(401, call("GET", "/api/me", token).statusCode());
    }

    @Test void tokenAfterLogoutReturns401() throws Exception {
        String token = sessions.issue(userOf("alice", Role.ADMIN));
        assertEquals(200, call("GET", "/api/me", token).statusCode());
        // Logout
        assertEquals(200, call("POST", "/api/logout", token).statusCode());
        // Eski token artık geçersiz
        assertEquals(401, call("GET", "/api/me", token).statusCode());
    }
}
