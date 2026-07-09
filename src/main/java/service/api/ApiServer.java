package service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.json.JavalinJackson;
import model.PaymentMethod;
import model.Product;
import model.Role;
import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.mindrot.jbcrypt.BCrypt;
import service.UserService;
import state.AppState;
import state.TableSnapshot;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mobil & uzaktan erişim için REST API katmanı.
 *
 * <p>Javalin tabanlı (gömülü Jetty). Spring Boot'tan ~50x hafiftir.
 * Mevcut Swing uygulamasıyla aynı JVM içinde çalışır, AppState singleton'ını
 * kullanır — yani UI'dan ne yapılıyorsa API'den de aynısı yapılır.
 *
 * <p><b>Endpoint'ler:</b>
 * <ul>
 *   <li>GET  /api/ping                        → health check (auth yok)</li>
 *   <li>POST /api/login                       → kullanıcı doğrulama</li>
 *   <li>GET  /api/me                          → mevcut kullanıcı bilgisi (auth)</li>
 *   <li>GET  /api/products                    → tüm aktif ürünler (auth)</li>
 *   <li>GET  /api/tables                      → kullanıcının erişebildiği masalar (auth)</li>
 *   <li>GET  /api/tables/:tableNo             → masa detay snapshot (auth)</li>
 *   <li>POST /api/tables/:tableNo/items       → masaya ürün ekle (auth, garson)</li>
 *   <li>POST /api/tables/:tableNo/sale        → satış tamamla (auth, kasiyer/admin)</li>
 * </ul>
 *
 * <p><b>Auth:</b> HTTP Basic — {@code Authorization: Basic base64(username:password)}.
 * BCrypt karşılaştırması yapılır; ham password fallback'i kaldırılmıştır.
 *
 * <p><b>Port:</b> 7070 (varsayılan). {@code -Dapi.port=8080} ile override edilebilir.
 */
public class ApiServer {

    private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);

    /** Auth bilgisini istek bağlamında tutmak için context key. */
    private static final String CTX_USER = "auth.user";
    private static final String CTX_REQUEST_ID = "request.id";

    private final AppState appState;
    private final UserService userService = new UserService();
    /** Bearer token oturum deposu — login sonrası rastgele 256-bit token. */
    private final SessionStore sessions = new SessionStore();
    /** Brute-force / rate-limit izleyici (kullanıcı:IP). */
    private final AuthFailureTracker authTracker = new AuthFailureTracker();

    /** Endpoint başına rate limiter'lar. */
    private final RateLimiter loginRl = new RateLimiter("login",
            SecurityConfig.loginRatePerMinute(), SecurityConfig.loginRatePerMinute() * 2);
    /** Admin/user-management — dakikada 60. */
    private final RateLimiter adminRl = new RateLimiter("admin", 60);
    /** Refund / iade — dakikada 20 (manuel kasiyer/admin işlemi). */
    private final RateLimiter refundRl = new RateLimiter("refund", 20);
    /** Backup/rapor download — dakikada 10. */
    private final RateLimiter reportRl = new RateLimiter("report", 10);
    private Javalin app;

    public ApiServer(AppState appState) {
        this.appState = appState;
    }

    /** API server'ı belirtilen portta başlatır. Daemon thread'de çalışır. */
    public synchronized void start(int port) {
        if (app != null) {
            LOG.warn("API server zaten çalışıyor");
            return;
        }
        // Jackson — Java 8 tarih tipleri için JSR-310 modülü
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // CORS allowlist — anyHost() KALDIRILDI. Origin'ler env üzerinden gelir.
        List<String> allowed = SecurityConfig.allowedOriginsOrEmpty();
        app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(mapper, true));
            // NOT: CORS yetkilendirme YERİNE GEÇMEZ — sadece tarayıcı politikasıdır.
            if (!allowed.isEmpty()) {
                cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                    for (String origin : allowed) {
                        rule.allowHost(origin);
                    }
                }));
            }
            // Request body limiti — DoS koruması
            cfg.http.maxRequestSize = SecurityConfig.maxBodyBytes();
            cfg.showJavalinBanner = false;
            // PWA statik dosyaları — /app/* yolundan resources/webapp/ klasörüne
            cfg.staticFiles.add(staticConfig -> {
                staticConfig.hostedPath = "/app";
                staticConfig.directory = "/webapp";
                staticConfig.location = io.javalin.http.staticfiles.Location.CLASSPATH;
                staticConfig.precompress = false;
            });
        });

        // 1) Her isteğe requestId + güvenlik header'ları ekle
        app.before(this::applyRequestId);
        app.after(this::applySecurityHeaders);
        // 2) Auth — /api/ping ve /api/login dışında tüm endpoint'ler için
        app.before("/api/*", this::authenticate);
        // 3) Endpoint bazlı rate limit (auth'tan SONRA — auth'lı kullanıcı bilgisi olsun)
        app.before("/api/users",       ctx -> rateLimited(ctx, adminRl, "users"));
        app.before("/api/users/*",     ctx -> rateLimited(ctx, adminRl, "users"));
        app.before("/api/refunds",     ctx -> rateLimited(ctx, refundRl, "refunds"));
        app.before("/api/reports/*",   ctx -> rateLimited(ctx, reportRl, "reports"));
        // 4) Genel hata yakalayıcı — stack trace sızdırmaz
        app.exception(Exception.class, this::handleUncaughtException);

        registerRoutes();

        app.start(port);
        LOG.info("REST API başladı: port={} (CORS allowlist: {})",
                port, allowed.isEmpty() ? "<boş — sadece same-origin>" : allowed);
    }

    public synchronized void stop() {
        if (app != null) {
            app.stop();
            app = null;
        }
        sessions.shutdown();
        authTracker.shutdown();
        loginRl.shutdown();
        adminRl.shutdown();
        refundRl.shutdown();
        reportRl.shutdown();
    }

    /** Tüm endpoint'leri kaydeder. */
    private void registerRoutes() {
        // Kök yol → PWA'ya yönlendir (mobil cihazlar için ana giriş)
        app.get("/", ctx -> ctx.redirect("/app/index.html"));
        // API yardım sayfası
        app.get("/api", this::landingPage);
        app.get("/api/", this::landingPage);
        app.get("/api/ping", this::ping);
        app.post("/api/login", this::login);
        app.post("/api/logout", this::logout);
        app.get("/api/me", this::me);
        app.get("/api/products", this::listProducts);
        app.get("/api/tables", this::listTables);
        app.get("/api/tables/{tableNo}", this::getTable);
        app.post("/api/tables/{tableNo}/lock", this::acquireTableLock);
        app.delete("/api/tables/{tableNo}/lock", this::releaseTableLock);
        app.post("/api/tables/{tableNo}/items", this::addItem);
        app.post("/api/tables/{tableNo}/sale", this::completeSale);
        app.post("/api/tables/{tableNo}/split-sale", this::splitSale);
        app.post("/api/tables/{tableNo}/send-to-kitchen", this::sendToKitchen);
        app.post("/api/tables/{tableNo}/mark-served", this::markServed);
        app.post("/api/tables/{tableNo}/decrease-item", this::decreaseItem);
        app.post("/api/tables/{tableNo}/remove-item", this::removeItem);
        app.delete("/api/tables/{tableNo}", this::clearTable);
        app.post("/api/tables/{tableNo}/transfer", this::transferTable);
        // Yeni raporlama endpoint'leri
        app.get("/api/sales", this::listSales);
        app.get("/api/orders/{orderId}/items", this::getOrderItems);
        app.get("/api/expenses", this::listExpenses);
        app.post("/api/expenses", this::createExpense);
        app.post("/api/expenses/kg", this::createKgExpense);
        app.get("/api/expense-templates", this::listExpenseTemplates);
        app.get("/api/refunds", this::listRefunds);
        app.get("/api/reports/daily", this::dailyReport);
        app.get("/api/reports/hourly", this::hourlyReport);
        app.get("/api/reports/quick", this::quickStat);
        app.get("/api/reports/staff-suggestions", this::staffSuggestions);
        app.get("/api/reports/product-summary", this::productSummaryReport);
        app.get("/api/reports/monthly", this::monthlyReport);
        // Ürün yönetimi
        app.get("/api/products/all", this::listAllProducts);
        app.post("/api/products", this::createProduct);
        app.patch("/api/products/{id}", this::updateProduct);
        app.post("/api/products/{id}/active", this::toggleProductActive);
        app.delete("/api/products/{id}", this::deleteProduct);
        app.get("/api/categories", this::listCategories);
        // Kullanıcı yönetimi (admin/kasiyer için)
        app.get("/api/users", this::listUsers);
        app.post("/api/users", this::createUser);
        app.post("/api/users/{id}/active", this::toggleUserActive);
        app.post("/api/users/{id}/reset-password", this::resetUserPassword);
        app.delete("/api/users/{id}", this::deleteUser);

        // Masa rezervasyonları
        app.get("/api/reservations", this::listReservations);
        app.post("/api/reservations", this::createReservation);
        app.post("/api/reservations/{id}/cancel", this::cancelReservation);
        app.post("/api/reservations/{id}/seat", this::seatReservation);
        app.post("/api/reservations/{id}/no-show", this::noShowReservation);
        app.get("/api/reservations/upcoming", this::upcomingReservations);
    }

    /** GET / — tarayıcı için endpoint listesi + auth bilgisi. */
    private void landingPage(Context ctx) {
        // HTML içeriği /api-help.html resource dosyasına taşındı.
        // Davranış aynı: HTML response, status 200, Content-Type text/html.
        String html;
        try (java.io.InputStream in = ApiServer.class.getResourceAsStream("/api-help.html")) {
            if (in == null) {
                LOG.warn("api-help.html resource bulunamadı");
                ctx.status(500).result("api-help.html bulunamadı");
                return;
            }
            html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            LOG.warn("api-help.html okunamadı: {}", ex.getMessage());
            ctx.status(500).result("api-help.html okunamadı");
            return;
        }
        ctx.html(html);
    }

    // ============================================================
    //   Auth
    // ============================================================

    private void authenticate(Context ctx) {
        String path = ctx.path();
        // Açık endpoint'ler — auth atla
        if (path.equals("/api/ping") || path.equals("/api/login") || path.equals("/")) {
            return;
        }
        String header = ctx.header("Authorization");
        if (header == null) {
            sendUnauthorized(ctx, "Authorization header gerekli");
            return;
        }
        // 1) Bearer token (tercih edilen yöntem) — BCrypt çağrısı YOK, O(1) lookup.
        if (header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<User> opt = sessions.lookup(token);
            if (opt.isEmpty()) {
                sendUnauthorized(ctx, "Geçersiz veya süresi dolmuş token");
                return;
            }
            ctx.attribute(CTX_USER, opt.get());
            return;
        }
        // 2) Basic Auth (geriye dönük uyumluluk için) — BCrypt çağrısı yapar.
        // TODO: Tüm istemciler Bearer'a geçince bu blok kaldırılabilir.
        if (header.startsWith("Basic ")) {
            try {
                String b64 = header.substring("Basic ".length()).trim();
                String decoded = new String(Base64.getDecoder().decode(b64));
                int colon = decoded.indexOf(':');
                if (colon < 0) {
                    sendUnauthorized(ctx, "Geçersiz auth formatı");
                    return;
                }
                String username = decoded.substring(0, colon);
                String password = decoded.substring(colon + 1);
                Optional<User> opt = verifyCredentials(username, password);
                if (opt.isEmpty()) {
                    sendUnauthorized(ctx, "Geçersiz kullanıcı/şifre");
                    return;
                }
                ctx.attribute(CTX_USER, opt.get());
            } catch (IllegalArgumentException ex) {
                sendUnauthorized(ctx, "Geçersiz base64");
            }
            return;
        }
        sendUnauthorized(ctx, "Authorization şeması desteklenmiyor (Bearer veya Basic kullanın)");
    }

    /** Bu istek için requestId üret ve context'e koy. */
    private void applyRequestId(Context ctx) {
        ctx.attribute(CTX_REQUEST_ID, UUID.randomUUID().toString());
    }

    /** Tüm yanıtlara güvenlik header'ları ekle. */
    private void applySecurityHeaders(Context ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
        // CSP — PWA inline style kullanıyor; TODO: inline stilleri kaldırıp 'unsafe-inline' atılabilir.
        ctx.header("Content-Security-Policy",
                "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "frame-ancestors 'none'");
        // HSTS sadece HTTPS modunda — kullanıcı HTTPS açtıysa
        if (SecurityConfig.httpsEnabled()) {
            ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        // Bu istek için üretilen requestId'i header'a ekle (debug için)
        Object rid = ctx.attribute(CTX_REQUEST_ID);
        if (rid != null) ctx.header("X-Request-Id", rid.toString());
    }

    /** Beklenmedik exception'ı stack trace sızdırmadan logla + güvenli yanıt dön. */
    private void handleUncaughtException(Exception ex, Context ctx) {
        Object rid = ctx.attribute(CTX_REQUEST_ID);
        String requestId = rid == null ? "?" : rid.toString();
        LOG.error("Yakalanmamış hata (requestId={}, path={}): {}",
                requestId, ctx.path(), ex.getMessage(), ex);
        ctx.status(500);
        ctx.json(Map.of(
                "error", "internal_error",
                "requestId", requestId));
    }

    /**
     * 401 + WWW-Authenticate header döndürür.
     * Bu header tarayıcının kullanıcı adı/şifre popup'ı açmasını tetikler.
     * Sonra Javalin'in skipRemainingHandlers metodunu çağırarak handler zincirini durdurur.
     */
    private void sendUnauthorized(Context ctx, String message) {
        ctx.status(401);
        ctx.header("WWW-Authenticate", "Basic realm=\"budgetController API\"");
        ctx.json(Map.of("error", message,
                "hint", "Tarayıcı popup'ında kullanıcı adı/şifre gir veya 'Authorization: Basic ...' header gönder"));
        ctx.skipRemainingHandlers();
    }

    /**
     * Verilen kullanıcı adı + şifreyle kullanıcıyı doğrular.
     * UserService.login() ile aynı mantığı kullanır (Swing'le tutarlı davranış).
     * BCrypt hash karşılaştırması; ham password fallback'i YOKtur.
     */
    private Optional<User> verifyCredentials(String username, String password) {
        if (username == null || password == null || username.isBlank()) {
            LOG.debug("API auth: boş kullanıcı/şifre");
            return Optional.empty();
        }
        // UserService.login() çağırıp tutarlı davranış sağla
        try {
            User user = userService.login(username.trim(), password);
            if (user == null) {
                LOG.warn("API auth FAIL: kullanıcı '{}' bulunamadı veya şifre yanlış", username);
                return Optional.empty();
            }
            LOG.debug("API auth OK: {}", username);
            return Optional.of(user);
        } catch (RuntimeException ex) {
            LOG.warn("API auth hatası ({}): {}", username, ex.getMessage());
            return Optional.empty();
        }
    }

    private User requireUser(Context ctx) {
        User user = ctx.attribute(CTX_USER);
        if (user == null) {
            throw new UnauthorizedResponse("Kullanıcı bağlam yok");
        }
        return user;
    }

    /**
     * Rate-limit kontrol — başarısızsa 429 + skip + true döner.
     * Tüketim yapılmışsa false (handler devam edebilir).
     */
    private boolean rateLimited(Context ctx, RateLimiter rl, String suffix) {
        User u = ctx.attribute(CTX_USER);
        String key = (u == null ? "anon" : u.getUsername()) + ":" + clientIp(ctx)
                + (suffix == null ? "" : ":" + suffix);
        if (!rl.tryAcquire(key)) {
            ctx.status(429).json(Map.of(
                    "error", "rate_limited",
                    "message", "Çok hızlı istek. Lütfen yavaşlayın."));
            ctx.skipRemainingHandlers();
            return true;
        }
        return false;
    }

    /**
     * Bu istek için kullanıcının rolü izinli rollerden biri mi?
     * Değilse 403 fırlatır. {@code null} listesi ile rol kontrolü yapılmaz.
     */
    private User requireRole(Context ctx, Role... allowed) {
        User user = requireUser(ctx);
        if (allowed == null || allowed.length == 0) return user;
        Role r = user.getRole();
        for (Role a : allowed) if (a == r) return user;
        ctx.status(403);
        ctx.json(Map.of("error", "forbidden",
                "message", "Bu işlem için yetkiniz yok"));
        ctx.skipRemainingHandlers();
        throw new UnauthorizedResponse("forbidden"); // Skip için bir exception gerek
    }

    // ============================================================
    //   Endpoint handler'lar
    // ============================================================

    private void ping(Context ctx) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "ok");
        body.put("service", "budgetController-api");
        body.put("time", java.time.LocalDateTime.now().toString());
        ctx.json(body);
    }

    /**
     * POST /api/login
     * Body: {"username":"...","password":"..."}
     * Response: {"token":"<opak>", "id":..., "username":"...", "role":"...", "fullName":"..."}
     *
     * Brute-force koruması: 5 başarısız → 15 dk kilit (kullanıcı:IP).
     */
    private void login(Context ctx) {
        Map<String, String> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception ex) {
            ctx.status(400).json(Map.of("error", "invalid_body"));
            return;
        }
        String username = body == null ? "" : body.getOrDefault("username", "");
        String password = body == null ? "" : body.getOrDefault("password", "");
        String ip = clientIp(ctx);

        // Genel login rate limit — IP başına dakikada API_LOGIN_RATE_PER_MIN
        if (!loginRl.tryAcquire("login:" + ip)) {
            ctx.status(429).json(Map.of("error", "rate_limited",
                    "message", "Çok fazla giriş denemesi. Lütfen yavaşlayın."));
            return;
        }
        if (authTracker.isLocked(username, ip)) {
            ctx.status(429).json(Map.of("error", "too_many_attempts",
                    "message", "Çok fazla başarısız deneme. Lütfen daha sonra tekrar deneyin."));
            return;
        }
        Optional<User> opt = verifyCredentials(username, password);
        if (opt.isEmpty()) {
            authTracker.recordFailure(username, ip);
            ctx.status(401).json(Map.of("error", "invalid_credentials"));
            return;
        }
        User user = opt.get();
        authTracker.recordSuccess(username, ip);
        service.audit.AuditLog.authSuccess(user.getUsername(), ip);
        String token = sessions.issue(user);
        Map<String, Object> resp = new HashMap<>();
        resp.put("token", token);
        resp.put("id", user.getId());
        resp.put("username", user.getUsername());
        resp.put("fullName", user.getFullName());
        resp.put("role", user.getRole() == null ? null : user.getRole().name());
        ctx.json(resp);
    }

    /** POST /api/logout — Bearer token'ı iptal eder. */
    private void logout(Context ctx) {
        String header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            // Logout audit'i için önce kullanıcıyı çöz, sonra revoke et
            sessions.lookup(token).ifPresent(u ->
                    service.audit.AuditLog.logout(u.getUsername()));
            sessions.revoke(token);
        }
        ctx.json(Map.of("status", "ok"));
    }

    /**
     * İstek geldiği IP. {@code X-Forwarded-For} SADECE güvenilen proxy
     * IP'lerinden gelen isteklerde okunur — aksi halde saldırgan kendi
     * header'ını gönderip rate-limit/brute-force tracker'ı yanıltabilir.
     *
     * <p>Güvenilen proxy listesi {@link SecurityConfig#trustedProxyIps()}'ten
     * gelir (varsayılan: 127.0.0.1, ::1).
     */
    private String clientIp(Context ctx) {
        String remote = ctx.ip();
        java.util.Set<String> trusted = SecurityConfig.trustedProxyIps();
        if (trusted.contains(remote)) {
            String fwd = ctx.header("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                int comma = fwd.indexOf(',');
                return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
            }
        }
        // Güvenilen proxy değilse direkt remote IP — XFF yok sayılır
        return remote;
    }

    private void me(Context ctx) {
        User user = requireUser(ctx);
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", user.getId());
        resp.put("username", user.getUsername());
        resp.put("fullName", user.getFullName());
        resp.put("role", user.getRole() == null ? null : user.getRole().name());
        ctx.json(resp);
    }

    private void listProducts(Context ctx) {
        List<Product> products = appState.getAvailableProducts();
        // Her ürüne kategori adını da ekle — mobil tarafı "İçecek" gibi kategorileri
        // tespit edip şiş bölümünü gizleyebilir.
        service.CategoryService categoryService = new service.CategoryService();
        java.util.Map<Long, String> categoryNames = new java.util.HashMap<>();
        for (model.Category c : categoryService.getAllCategories()) {
            if (c != null && c.getId() != null) {
                categoryNames.put(c.getId(), c.getName());
            }
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Product p : products) {
            if (p == null) continue;
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("unitPrice", p.getUnitPrice());
            m.put("active", p.isActive());
            m.put("categoryId", p.getCategoryId());
            m.put("categoryName", p.getCategoryId() == null
                    ? null : categoryNames.get(p.getCategoryId()));
            m.put("piecesPerPortion", p.getPiecesPerPortion());
            m.put("unitLabel", p.getUnitLabel());
            out.add(m);
        }
        ctx.json(out);
    }

    /**
     * GET /api/tables
     * Kullanıcının erişebildiği tüm masaların özetini döner (bina/kat/salon/no/status).
     */
    private void listTables(Context ctx) {
        User user = requireUser(ctx);
        List<AppState.AreaDefinition> areas = appState.getAccessibleAreas(user);
        List<Map<String, Object>> tables = new java.util.ArrayList<>();
        for (AppState.AreaDefinition area : areas) {
            for (Integer tableNo : area.getTableNumbers()) {
                TableSnapshot snap = appState.snapshot(tableNo);
                Map<String, Object> t = new HashMap<>();
                t.put("tableNo", tableNo);
                t.put("building", area.getBuilding());
                t.put("floor", area.getSection());
                t.put("salon", area.getSalon());
                t.put("status", snap.getStatus() == null ? null : snap.getStatus().name());
                t.put("total", snap.getTotal());
                tables.add(t);
            }
        }
        ctx.json(tables);
    }

    /** GET /api/tables/{tableNo} — masa snapshot detayı */
    private void getTable(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        if (!appState.canAccessTable(tableNo, user)) {
            ctx.status(403).json(Map.of("error", "Bu masaya erişim yetkiniz yok"));
            return;
        }
        TableSnapshot snap = appState.snapshot(tableNo);
        ctx.json(snap);
    }

    /**
     * POST /api/tables/{tableNo}/items
     * Body: {
     *   "productId": ...,
     *   "quantity": ...,           // porsiyon bazlı: porsiyon sayısı; şiş bazlı: toplam şiş
     *   "pieces": ...,             // opsiyonel — şiş bazlı ürünlerde toplam şiş sayısı
     *   "note": "soğansız, ..."    // opsiyonel — kaleme not eklenir
     * }
     */
    private void addItem(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        if (!appState.canAccessTable(tableNo, user)) {
            ctx.status(403).json(Map.of("error", "Bu masaya erişim yetkiniz yok"));
            return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Long productId = toLong(body.get("productId"));
        int qty = toInt(body.get("quantity"), 1);
        Integer pieces = body.get("pieces") == null ? null : toInt(body.get("pieces"), 0);
        String note = body.get("note") == null ? null : body.get("note").toString().trim();
        if (productId == null || productId <= 0) {
            ctx.status(400).json(Map.of("error", "productId gerekli"));
            return;
        }
        try {
            // Stage 0G: guard + add + not tek AppState wrapper'ında.
            // Şiş bazlı yol wrapper'a non-null pieces ile seçilir (mevcut >0 kuralı korunur).
            Integer piecesArg = (pieces != null && pieces > 0) ? pieces : null;
            model.ItemAddWithNoteResult result =
                    appState.addItemWithNote(tableNo, productId, qty, piecesArg, note, user);
            if (!result.itemAdded()) {
                // Not çakışması — ürün HİÇ eklenmedi; noteStatus üretilmez.
                ctx.status(409).json(Map.of("error",
                        "Ürün siparişte farklı bir notla zaten bulunuyor. "
                        + "Farklı notlu ürünler henüz ayrı satır olarak desteklenmediği için ürün eklenmedi."));
                return;
            }
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("status", "added");
            resp.put("tableNo", tableNo);
            resp.put("productId", productId);
            resp.put("quantity", qty);
            resp.put("pieces", pieces == null ? 0 : pieces);
            resp.put("note", note == null ? "" : note);
            if (result.noteResult() != null) {
                resp.put("noteStatus", result.noteResult().name());
            }
            ctx.json(resp);
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/tables/{tableNo}/sale
     * Body: {"method":"CASH|CREDIT_CARD|TRANSFER"}
     */
    private void completeSale(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Satış için Admin/Kasiyer yetkisi gerekir"));
            return;
        }
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String methodStr = String.valueOf(body.getOrDefault("method", "CASH"));
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(methodStr);
        } catch (IllegalArgumentException ex) {
            ctx.status(400).json(Map.of("error", "Geçersiz ödeme yöntemi: " + methodStr));
            return;
        }
        try {
            appState.recordSale(tableNo, method, user);
            ctx.json(Map.of("status", "completed", "tableNo", tableNo,
                    "method", method.name()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/tables/{tableNo}/split-sale
     * Body: {"parts": [{"amount": 50.00, "method": "CASH"}, {"amount": 50.00, "method": "CREDIT_CARD"}]}
     */
    private void splitSale(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Hesap bölme için Admin/Kasiyer yetkisi gerekir"));
            return;
        }
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object partsRaw = body.get("parts");
        if (!(partsRaw instanceof List<?>)) {
            ctx.status(400).json(Map.of("error", "parts dizisi gerekli"));
            return;
        }
        List<state.AppState.SplitPart> parts = new java.util.ArrayList<>();
        for (Object pObj : (List<?>) partsRaw) {
            if (!(pObj instanceof Map<?, ?>)) continue;
            Map<?, ?> p = (Map<?, ?>) pObj;
            BigDecimal amount = toBigDecimal(p.get("amount"));
            PaymentMethod method;
            try {
                method = PaymentMethod.valueOf(String.valueOf(p.get("method")));
            } catch (IllegalArgumentException ex) {
                ctx.status(400).json(Map.of("error", "Geçersiz ödeme yöntemi: " + p.get("method")));
                return;
            }
            parts.add(new state.AppState.SplitPart(amount, method));
        }
        try {
            appState.recordSplitSale(tableNo, user, parts);
            ctx.json(Map.of("status", "completed", "tableNo", tableNo,
                    "partCount", parts.size()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/tables/{tableNo}/lock — masayı kilitle */
    private void acquireTableLock(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        boolean ok = appState.acquireTableLock(tableNo, user);
        if (ok) {
            ctx.json(Map.of("status", "locked", "tableNo", tableNo));
        } else {
            state.AppState.TableLock lock = appState.getTableLock(tableNo);
            String holder = lock == null ? "?" : lock.userName;
            ctx.status(409).json(Map.of(
                    "error", "Bu masa şu anda " + holder + " tarafından kullanılıyor",
                    "holder", holder));
        }
    }

    /** DELETE /api/tables/{tableNo}/lock — kilidi bırak */
    private void releaseTableLock(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        appState.releaseTableLock(tableNo, user);
        ctx.json(Map.of("status", "released"));
    }

    /**
     * POST /api/tables/{tableNo}/mark-served
     * Sipariş masaya servis edildi → masa SERVED durumuna geçer (yeşil).
     */
    private void markServed(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        if (!appState.canAccessTable(tableNo, user)) {
            ctx.status(403).json(Map.of("error", "Bu masaya erişim yetkiniz yok"));
            return;
        }
        try {
            appState.markServed(tableNo, user);
            ctx.json(Map.of("status", "served", "tableNo", tableNo));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/tables/{tableNo}/send-to-kitchen */
    private void sendToKitchen(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        if (!appState.canAccessTable(tableNo, user)) {
            ctx.status(403).json(Map.of("error", "Bu masaya erişim yetkiniz yok"));
            return;
        }
        try {
            // PrintingService null verirsek mutfak basımı atlanır ama log atılır
            // Mevcut singleton'ı yoksa, sadece state'i günceller (yeni kalemleri pending'ten çıkarır)
            service.print.PrintingService printing = null;  // PWA'dan basım yok, sadece state
            List<service.print.PrintingService.PrintResult> results =
                    appState.sendOrderToKitchens(tableNo, user, printing);
            ctx.json(Map.of(
                    "status", "sent",
                    "tableNo", tableNo,
                    "kitchenCount", results == null ? 0 : results.size()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/tables/{tableNo}/decrease-item
     * Body: {"productName":"...", "quantity":1, "reason":"..."}
     */
    private void decreaseItem(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String productName = String.valueOf(body.getOrDefault("productName", ""));
        int qty = toInt(body.get("quantity"), 1);
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        try {
            appState.decreaseItem(tableNo, productName, qty, user, reason);
            ctx.json(Map.of("status", "decreased", "productName", productName, "quantity", qty));
        } catch (SecurityException ex) {
            ctx.status(403).json(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/tables/{tableNo}/remove-item
     * Body: {"productName":"...", "reason":"..."}
     */
    private void removeItem(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String productName = String.valueOf(body.getOrDefault("productName", ""));
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        try {
            appState.removeItem(tableNo, productName, user, reason);
            ctx.json(Map.of("status", "removed", "productName", productName));
        } catch (SecurityException ex) {
            ctx.status(403).json(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * DELETE /api/tables/{tableNo}?reason=...
     * Masayı temizler (admin/kasiyer).
     */
    private void clearTable(Context ctx) {
        User user = requireUser(ctx);
        int tableNo = Integer.parseInt(ctx.pathParam("tableNo"));
        String reason = ctx.queryParam("reason");
        try {
            appState.clearTable(tableNo, user, reason);
            ctx.json(Map.of("status", "cleared", "tableNo", tableNo));
        } catch (SecurityException ex) {
            ctx.status(403).json(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/tables/{tableNo}/transfer
     * Body: {"targetTableNo": 102}
     */
    private void transferTable(Context ctx) {
        User user = requireUser(ctx);
        int fromTable = Integer.parseInt(ctx.pathParam("tableNo"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        int toTable = toInt(body.get("targetTableNo"), -1);
        if (toTable <= 0) {
            ctx.status(400).json(Map.of("error", "targetTableNo gerekli"));
            return;
        }
        try {
            appState.transferTable(fromTable, toTable, user);
            ctx.json(Map.of("status", "transferred",
                    "from", fromTable, "to", toTable));
        } catch (SecurityException ex) {
            ctx.status(403).json(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    // ============================================================
    //   Raporlama endpoint'leri
    // ============================================================

    /**
     * GET /api/sales?date=YYYY-MM-DD
     * Verilen gün için tüm satış kayıtları (admin için tablo, kasiyer kendi günü).
     */
    private void listSales(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz"));
            return;
        }
        String dateStr = ctx.queryParam("date");
        java.time.LocalDate date = (dateStr == null || dateStr.isBlank())
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(dateStr);
        // Payment objelerini direkt al — orderId bilgisi gerekiyor (kalemler için)
        service.PaymentService paymentService = new service.PaymentService();
        service.OrderService orderService = new service.OrderService();
        service.RestaurantTableService tableService = new service.RestaurantTableService();
        List<model.Payment> payments = paymentService.getPaymentsOn(date);
        BigDecimal total = payments.stream()
                .map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.Payment p : payments) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("orderId", p.getOrderId());
            m.put("amount", p.getAmount());
            m.put("method", p.getMethod() == null ? null : p.getMethod().name());
            m.put("timestamp", p.getPaidAt() == null
                    ? (p.getCreatedAt() == null ? null : p.getCreatedAt().toString())
                    : p.getPaidAt().toString());
            // Order'dan masa ve garson bilgisi
            if (p.getOrderId() != null) {
                model.Order order = orderService.getOrderById(p.getOrderId()).orElse(null);
                if (order != null && order.getTableId() != null) {
                    model.RestaurantTable t = tableService.getTableById(order.getTableId()).orElse(null);
                    if (t != null) m.put("tableNo", t.getTableNo());
                }
            }
            // Cashier (kasiyer) adı
            if (p.getCashierId() != null) {
                model.User u = userService.getUserById(p.getCashierId()).orElse(null);
                if (u != null) {
                    m.put("performer", u.getFullName() != null && !u.getFullName().isBlank()
                            ? u.getFullName() : u.getUsername());
                }
            }
            out.add(m);
        }
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("date", date.toString());
        resp.put("count", payments.size());
        resp.put("sales", out);
        // KASIYER: toplam ciro gizli — sadece ADMIN görür
        if (user.getRole() == Role.ADMIN) {
            resp.put("total", total);
        }
        ctx.json(resp);
    }

    /** GET /api/orders/{orderId}/items — bir siparişin tüm kalemleri */
    private void getOrderItems(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz"));
            return;
        }
        Long orderId = Long.parseLong(ctx.pathParam("orderId"));
        service.OrderService orderService = new service.OrderService();
        service.ProductService productService = new service.ProductService();
        List<model.OrderItem> items = orderService.getItemsForOrder(orderId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.OrderItem it : items) {
            if (it == null) continue;
            Map<String, Object> m = new java.util.HashMap<>();
            String name = it.getProductName();
            if ((name == null || name.isBlank()) && it.getProductId() != null) {
                Product p = productService.getProductById(it.getProductId());
                if (p != null) name = p.getName();
            }
            m.put("name", name);
            m.put("quantity", it.getQuantity());
            m.put("unitPrice", it.getUnitPrice());
            m.put("lineTotal", it.getLineTotal());
            m.put("note", it.getNote());
            out.add(m);
        }
        ctx.json(out);
    }

    /**
     * GET /api/expenses?date=YYYY-MM-DD (opsiyonel — verilmezse bugün)
     */
    private void listExpenses(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz"));
            return;
        }
        String dateStr = ctx.queryParam("date");
        java.time.LocalDate date = (dateStr == null || dateStr.isBlank())
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(dateStr);
        List<state.ExpenseRecord> expenses = appState.getExpensesOn(date);
        BigDecimal total = expenses.stream()
                .map(e -> e.getAmount() == null ? BigDecimal.ZERO : e.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (state.ExpenseRecord e : expenses) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", e.getId());
            m.put("amount", e.getAmount());
            m.put("description", e.getDescription());
            m.put("performer", e.getPerformedBy());  // getPerformer DEĞİL
            m.put("expenseDate", e.getExpenseDate() == null ? null : e.getExpenseDate().toString());
            m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            out.add(m);
        }
        ctx.json(Map.of("date", date.toString(),
                "total", total,
                "count", expenses.size(),
                "expenses", out));
    }

    /**
     * POST /api/expenses
     * Body: {"amount":..., "description":"...", "date":"YYYY-MM-DD" (opsiyonel)}
     */
    private void createExpense(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz"));
            return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        BigDecimal amount = toBigDecimal(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            ctx.status(400).json(Map.of("error", "amount gerekli (>0)"));
            return;
        }
        String description = body.get("description") == null
                ? "" : body.get("description").toString().trim();
        if (description.isEmpty()) {
            ctx.status(400).json(Map.of("error", "açıklama gerekli"));
            return;
        }
        java.time.LocalDate date = body.get("date") == null || body.get("date").toString().isBlank()
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(body.get("date").toString());
        try {
            appState.addExpense(amount, description, date, user);
            ctx.json(Map.of("status", "added", "amount", amount, "description", description));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * POST /api/expenses/kg
     * Body: {"description":"Domates", "quantityKg":3, "unitPricePerKg":25, "date":"YYYY-MM-DD"}
     */
    private void createKgExpense(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String description = body.get("description") == null
                ? "" : body.get("description").toString().trim();
        BigDecimal kg = toBigDecimal(body.get("quantityKg"));
        BigDecimal unitPrice = toBigDecimal(body.get("unitPricePerKg"));
        if (description.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Açıklama gerekli")); return;
        }
        if (kg == null || kg.signum() <= 0) {
            ctx.status(400).json(Map.of("error", "Kilo > 0 olmalı")); return;
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            ctx.status(400).json(Map.of("error", "Geçerli kg fiyatı girin")); return;
        }
        java.time.LocalDate date = body.get("date") == null || body.get("date").toString().isBlank()
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(body.get("date").toString());
        try {
            appState.addKgBasedExpense(description, kg, unitPrice, date, user);
            BigDecimal total = kg.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP);
            ctx.json(Map.of("status", "added", "total", total));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * GET /api/expense-templates
     * Hazır gider seçeneklerini döner — sahip için kolaylık.
     * Properties dosyasından okunur: ~/.budget/expense-templates.properties
     * (veya resources/expense-templates.properties).
     */
    private void listExpenseTemplates(Context ctx) {
        requireUser(ctx);
        // Properties dosyasından ortak şablon listesi (Swing ile aynı kaynak)
        List<service.expense.ExpenseTemplate> shared =
                service.expense.ExpenseTemplateService.loadTemplates();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (service.expense.ExpenseTemplate t : shared) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", t.name());
            m.put("icon", t.icon());
            m.put("defaultMode", t.defaultMode());
            result.add(m);
        }
        ctx.json(result);
    }


    /** GET /api/refunds — admin için iade/iptal log'ları. */
    private void listRefunds(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin"));
            return;
        }
        List<model.RefundLog> logs = appState.getAllRefundLogs();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.RefundLog l : logs) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", l.getId());
            m.put("userName", l.getUserName());
            m.put("actionType", l.getActionType() == null ? null : l.getActionType().name());
            m.put("tableNo", l.getTableNo());
            m.put("productName", l.getProductName());
            m.put("quantity", l.getQuantity());
            m.put("amount", l.getAmount());
            m.put("reason", l.getReason());
            m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
            out.add(m);
        }
        ctx.json(out);
    }

    /** GET /api/reports/daily?date=YYYY-MM-DD — admin için günlük özet. */
    private void dailyReport(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin"));
            return;
        }
        String dateStr = ctx.queryParam("date");
        java.time.LocalDate date = (dateStr == null || dateStr.isBlank())
                ? java.time.LocalDate.now()
                : java.time.LocalDate.parse(dateStr);
        BigDecimal salesTotal = appState.getSalesTotal(date);
        BigDecimal expenseTotal = appState.getExpenseTotal(date);
        BigDecimal netProfit = salesTotal.subtract(expenseTotal)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        int salesCount = appState.getSalesOn(date).size();
        int expenseCount = appState.getExpensesOn(date).size();
        ctx.json(Map.of(
                "date", date.toString(),
                "salesTotal", salesTotal,
                "expenseTotal", expenseTotal,
                "netProfit", netProfit,
                "salesCount", salesCount,
                "expenseCount", expenseCount
        ));
    }

    /**
     * GET /api/reports/product-summary?date=YYYY-MM-DD VEYA ?month=YYYY-MM
     * Ürün satış özeti — Swing DailyReportPanel'daki ile aynı.
     * Her satır: productName, unitLabel, totalQty, piecesPerPortion, portionEquivalent
     */
    private void productSummaryReport(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin")); return;
        }
        String dateStr = ctx.queryParam("date");
        String monthStr = ctx.queryParam("month");
        java.time.LocalDate from, to;
        if (monthStr != null && !monthStr.isBlank()) {
            java.time.YearMonth ym = java.time.YearMonth.parse(monthStr);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        } else {
            java.time.LocalDate d = (dateStr == null || dateStr.isBlank())
                    ? java.time.LocalDate.now() : java.time.LocalDate.parse(dateStr);
            from = d;
            to = d;
        }

        final String sql =
                "SELECT oi.product_name, " +
                "       COALESCE(oi.unit_label, '') AS unit_label, " +
                "       COALESCE(MAX(oi.pieces_per_portion), 0) AS pp, " +
                "       SUM(oi.quantity) AS total_qty, " +
                "       SUM(oi.line_total) AS total_amount " +
                "  FROM order_items oi " +
                "  JOIN payments    p ON p.order_id = oi.order_id " +
                " WHERE DATE(p.paid_at) BETWEEN ? AND ? " +
                " GROUP BY oi.product_name, COALESCE(oi.unit_label, '') " +
                " ORDER BY total_qty DESC";
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try (java.sql.Connection c = DataConnection.Db.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(from));
            ps.setDate(2, java.sql.Date.valueOf(to));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new java.util.HashMap<>();
                    String name = rs.getString("product_name");
                    String unit = rs.getString("unit_label");
                    int qty = rs.getInt("total_qty");
                    int pp = rs.getInt("pp");
                    BigDecimal amt = rs.getBigDecimal("total_amount");
                    String effectiveUnit = (unit == null || unit.isBlank())
                            ? (pp > 0 ? "şiş" : "porsiyon") : unit;
                    m.put("productName", name);
                    m.put("unitLabel", effectiveUnit);
                    m.put("totalQty", qty);
                    m.put("piecesPerPortion", pp);
                    m.put("portionEquivalent", pp > 0 ? (qty / (double) pp) : qty);
                    m.put("totalAmount", amt);
                    out.add(m);
                }
            }
        } catch (java.sql.SQLException ex) {
            // Snapshot sütunları yoksa fallback
            tryFallbackProductSummary(out, from, to);
        }
        Map<String, Object> resp = new java.util.HashMap<>();
        resp.put("from", from.toString());
        resp.put("to", to.toString());
        resp.put("count", out.size());
        resp.put("rows", out);
        ctx.json(resp);
    }

    private void tryFallbackProductSummary(List<Map<String, Object>> out,
                                           java.time.LocalDate from,
                                           java.time.LocalDate to) {
        final String sql =
                "SELECT oi.product_name, SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments p ON p.order_id = oi.order_id " +
                " WHERE DATE(p.paid_at) BETWEEN ? AND ? " +
                " GROUP BY oi.product_name " +
                " ORDER BY total_qty DESC";
        try (java.sql.Connection c = DataConnection.Db.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(from));
            ps.setDate(2, java.sql.Date.valueOf(to));
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("productName", rs.getString("product_name"));
                    m.put("unitLabel", "porsiyon");
                    int qty = rs.getInt("total_qty");
                    m.put("totalQty", qty);
                    m.put("piecesPerPortion", 0);
                    m.put("portionEquivalent", qty);
                    out.add(m);
                }
            }
        } catch (java.sql.SQLException e) {
            LOG.warn("Product summary fallback query failed; returning partial/empty result: {}", e.toString());
        }
    }

    /** GET /api/reports/monthly?month=YYYY-MM — aylık özet */
    private void monthlyReport(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin")); return;
        }
        String monthStr = ctx.queryParam("month");
        java.time.YearMonth ym = (monthStr == null || monthStr.isBlank())
                ? java.time.YearMonth.now() : java.time.YearMonth.parse(monthStr);
        BigDecimal salesTotal = appState.getSalesTotal(ym);
        BigDecimal expenseTotal = appState.getExpenseTotal(ym);
        BigDecimal netProfit = salesTotal.subtract(expenseTotal)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        // Sipariş sayısı
        java.time.LocalDate from = ym.atDay(1);
        int salesCount = 0, expCount = 0;
        int days = ym.lengthOfMonth();
        for (int i = 0; i < days; i++) {
            java.time.LocalDate d = from.plusDays(i);
            salesCount += appState.getSalesOn(d).size();
            expCount += appState.getExpensesOn(d).size();
        }
        ctx.json(Map.of(
                "month", ym.toString(),
                "salesTotal", salesTotal,
                "expenseTotal", expenseTotal,
                "netProfit", netProfit,
                "salesCount", salesCount,
                "expenseCount", expCount
        ));
    }

    /** GET /api/reports/quick?type=... — hızlı istatistik chip'leri için */
    private void quickStat(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin")); return;
        }
        String type = ctx.queryParam("type");
        if (type == null) type = "today-sales";
        java.time.LocalDate today = java.time.LocalDate.now();
        Map<String, Object> out = new java.util.HashMap<>();
        switch (type) {
            case "today-sales" -> {
                BigDecimal total = appState.getSalesTotal(today);
                int count = appState.getSalesOn(today).size();
                out.put("title", "Bugün Satış");
                out.put("value", "₺" + total.toPlainString());
                out.put("detail", count + " satış");
            }
            case "week-sales" -> {
                java.time.LocalDate from = today.minusDays(6);
                BigDecimal sum = BigDecimal.ZERO;
                int count = 0;
                for (int i = 0; i < 7; i++) {
                    java.time.LocalDate d = from.plusDays(i);
                    sum = sum.add(appState.getSalesTotal(d));
                    count += appState.getSalesOn(d).size();
                }
                out.put("title", "Son 7 Gün");
                out.put("value", "₺" + sum.toPlainString());
                out.put("detail", count + " satış");
            }
            case "month-sales" -> {
                java.time.YearMonth ym = java.time.YearMonth.from(today);
                BigDecimal sum = appState.getSalesTotal(ym);
                out.put("title", "Bu Ay");
                out.put("value", "₺" + sum.toPlainString());
                out.put("detail", ym.toString());
            }
            case "today-expenses" -> {
                BigDecimal exp = appState.getExpenseTotal(today);
                int count = appState.getExpensesOn(today).size();
                out.put("title", "Bugün Gider");
                out.put("value", "₺" + exp.toPlainString());
                out.put("detail", count + " gider");
            }
            case "net-profit" -> {
                BigDecimal net = appState.getNetProfit(today);
                out.put("title", "Bugün Net Kar");
                out.put("value", (net.signum() >= 0 ? "+ ₺" : "− ₺") + net.abs().toPlainString());
                out.put("detail", "Satış - Gider");
            }
            case "top-products" -> {
                // En çok satılan ürün
                List<state.SaleRecord> sales = appState.getSalesOn(today);
                out.put("title", "En Çok Satılan (Bugün)");
                out.put("value", sales.size() + " satış");
                out.put("detail", "Detay için 'Satışlar' panelini açın");
            }
            default -> {
                ctx.status(400).json(Map.of("error", "Bilinmeyen tip: " + type));
                return;
            }
        }
        ctx.json(out);
    }

    /**
     * GET /api/reports/staff-suggestions — geçmiş 30 gün verisinden
     * saatlik personel önerisi (basit kural: > 10 sipariş/saat → 2 garson, 20 → 3, ...).
     */
    private void staffSuggestions(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin")); return;
        }
        // Son 30 günün her saati için ortalama sipariş adedi
        java.time.LocalDate today = java.time.LocalDate.now();
        int[][] dayHourCount = new int[7][24];  // [hafta günü 0=Pzt..6=Pzr][saat]
        int[][] dayHourTotal = new int[7][24];
        for (int d = 0; d < 30; d++) {
            java.time.LocalDate day = today.minusDays(d);
            int dow = (day.getDayOfWeek().getValue() - 1) % 7;
            for (state.SaleRecord s : appState.getSalesOn(day)) {
                if (s.getTimestamp() == null) continue;
                int h = s.getTimestamp().getHour();
                dayHourCount[dow][h]++;
            }
            for (int h = 0; h < 24; h++) {
                dayHourTotal[dow][h] += 1;  // gün sayısı
            }
        }
        String[] dayNames = {"Pazartesi","Salı","Çarşamba","Perşembe","Cuma","Cumartesi","Pazar"};
        List<Map<String, Object>> suggestions = new java.util.ArrayList<>();
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                if (dayHourTotal[d][h] == 0) continue;
                double avg = dayHourCount[d][h] / (double) (dayHourTotal[d][h] / 30.0 + 0.001);
                if (avg < 5) continue;  // boş saat — atla
                int staff = avg < 10 ? 1 : (avg < 20 ? 2 : (avg < 35 ? 3 : 4));
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("day", dayNames[d]);
                m.put("hour", String.format("%02d:00", h));
                m.put("avgOrders", Math.round(avg * 10) / 10.0);
                m.put("suggestedStaff", staff);
                suggestions.add(m);
            }
        }
        ctx.json(suggestions);
    }

    /** GET /api/reports/hourly?date=YYYY-MM-DD — 24 saatlik satış adedi + tutar */
    private void hourlyReport(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin"));
            return;
        }
        String dateStr = ctx.queryParam("date");
        java.time.LocalDate date = (dateStr == null || dateStr.isBlank())
                ? java.time.LocalDate.now() : java.time.LocalDate.parse(dateStr);
        List<state.SaleRecord> sales = appState.getSalesOn(date);
        int[] counts = new int[24];
        BigDecimal[] amounts = new BigDecimal[24];
        for (int i = 0; i < 24; i++) amounts[i] = BigDecimal.ZERO;
        for (state.SaleRecord s : sales) {
            if (s.getTimestamp() == null) continue;
            int h = s.getTimestamp().getHour();
            counts[h]++;
            if (s.getTotal() != null) amounts[h] = amounts[h].add(s.getTotal());
        }
        List<Map<String, Object>> hours = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("hour", h);
            m.put("count", counts[h]);
            m.put("amount", amounts[h]);
            hours.add(m);
        }
        ctx.json(Map.of("date", date.toString(), "hours", hours));
    }

    // ============================================================
    //   Ürün yönetimi
    // ============================================================

    /** GET /api/products/all — pasif dahil tüm ürünler (admin/kasiyer için). */
    private void listAllProducts(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz"));
            return;
        }
        List<Product> products = appState.getAllProductsIncludingInactive();
        service.CategoryService categoryService = new service.CategoryService();
        java.util.Map<Long, String> categoryNames = new java.util.HashMap<>();
        for (model.Category c : categoryService.getAllCategories()) {
            if (c != null && c.getId() != null) categoryNames.put(c.getId(), c.getName());
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Product p : products) {
            if (p == null) continue;
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("unitPrice", p.getUnitPrice());
            m.put("active", p.isActive());
            m.put("categoryId", p.getCategoryId());
            m.put("categoryName", p.getCategoryId() == null
                    ? null : categoryNames.get(p.getCategoryId()));
            m.put("piecesPerPortion", p.getPiecesPerPortion());
            m.put("unitLabel", p.getUnitLabel());
            out.add(m);
        }
        ctx.json(out);
    }

    /** POST /api/products — yeni ürün */
    private void createProduct(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        BigDecimal price = toBigDecimal(body.get("unitPrice"));
        Long categoryId = toLong(body.get("categoryId"));
        if (name.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Ad gerekli")); return;
        }
        if (price == null || price.signum() < 0) {
            ctx.status(400).json(Map.of("error", "Geçerli fiyat girin")); return;
        }
        try {
            Product p = new Product();
            p.setName(name);
            p.setUnitPrice(price);
            p.setVatRate(Product.DEFAULT_VAT);
            p.setStock(0);
            p.setActive(true);
            p.setCategoryId(categoryId);
            Object pp = body.get("piecesPerPortion");
            if (pp != null) {
                int piecesPP = toInt(pp, 0);
                if (piecesPP > 0) p.setPiecesPerPortion(piecesPP);
            }
            Object ul = body.get("unitLabel");
            if (ul != null) p.setUnitLabel(ul.toString());
            Long id = appState.createProduct(p);
            ctx.json(Map.of("status", "created", "id", id, "name", name));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** PATCH /api/products/{id} — güncelle */
    private void updateProduct(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        try {
            Product p = new service.ProductService().getProductById(id);
            if (p == null) { ctx.status(404).json(Map.of("error", "Bulunamadı")); return; }
            if (body.containsKey("name")) p.setName(body.get("name").toString().trim());
            if (body.containsKey("unitPrice")) p.setUnitPrice(toBigDecimal(body.get("unitPrice")));
            if (body.containsKey("categoryId")) p.setCategoryId(toLong(body.get("categoryId")));
            if (body.containsKey("unitLabel")) p.setUnitLabel(body.get("unitLabel") == null ? null : body.get("unitLabel").toString());
            if (body.containsKey("piecesPerPortion")) {
                Object v = body.get("piecesPerPortion");
                p.setPiecesPerPortion(v == null ? null : toInt(v, 0));
            }
            appState.updateProduct(p);
            ctx.json(Map.of("status", "updated", "id", id));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/products/{id}/active — tükendi/stokta toggle */
    private void toggleProductActive(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        boolean active = Boolean.parseBoolean(String.valueOf(body.getOrDefault("active", true)));
        try {
            appState.setProductActive(id, active);
            ctx.json(Map.of("status", "ok", "id", id, "active", active));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** DELETE /api/products/{id} */
    private void deleteProduct(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Sadece Admin"));
            return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        try {
            appState.deleteProduct(id);
            ctx.json(Map.of("status", "deleted", "id", id));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** GET /api/categories — tüm kategoriler */
    private void listCategories(Context ctx) {
        requireUser(ctx);
        List<model.Category> cats = appState.getAllCategories();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.Category c : cats) {
            if (c == null || !c.isActive()) continue;
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            out.add(m);
        }
        ctx.json(out);
    }

    // ============================================================
    //   Kullanıcı yönetimi
    // ============================================================

    /** GET /api/users — ADMIN kullanıcılar listede gösterilmez (Swing AdminPanel ile aynı politika) */
    private void listUsers(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        List<User> users = userService.getAllUsers();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (User u : users) {
            if (u.getRole() == Role.ADMIN) continue; // ADMIN kullanıcılar listede gösterilmez
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("fullName", u.getFullName());
            m.put("role", u.getRole() == null ? null : u.getRole().name());
            m.put("active", u.isActive());
            out.add(m);
        }
        ctx.json(out);
    }

    /** POST /api/users — yeni kasiyer/garson */
    private void createUser(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String username = body.get("username") == null ? "" : body.get("username").toString().trim();
        String password = body.get("password") == null ? "" : body.get("password").toString();
        String fullName = body.get("fullName") == null ? username : body.get("fullName").toString().trim();
        String roleStr = body.get("role") == null ? "GARSON" : body.get("role").toString().toUpperCase();
        if (username.isEmpty() || password.isEmpty()) {
            ctx.status(400).json(Map.of("error", "Kullanıcı adı + şifre gerekli")); return;
        }
        // ADMIN UI'dan oluşturulamaz
        Role role;
        try { role = Role.valueOf(roleStr); }
        catch (IllegalArgumentException ex) {
            ctx.status(400).json(Map.of("error", "Geçersiz rol")); return;
        }
        if (role == Role.ADMIN) {
            ctx.status(403).json(Map.of("error", "Admin sadece DB'den eklenebilir"));
            return;
        }
        try {
            Long id = userService.createUser(username, password, role, fullName);
            ctx.json(Map.of("status", "created", "id", id, "username", username));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/users/{id}/active — pasif/aktif toggle */
    private void toggleUserActive(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        try {
            User target = userService.getUserById(id).orElse(null);
            if (target == null) { ctx.status(404).json(Map.of("error", "Bulunamadı")); return; }
            if (target.getRole() == Role.ADMIN) {
                ctx.status(403).json(Map.of("error", "Admin değiştirilemez")); return;
            }
            target.setActive(!target.isActive());
            userService.updateUser(target);
            ctx.json(Map.of("status", "ok", "active", target.isActive()));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** POST /api/users/{id}/reset-password — şifre değiştir */
    private void resetUserPassword(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        String pwd = body.get("password") == null ? "" : body.get("password").toString();
        if (pwd.length() < 4) {
            ctx.status(400).json(Map.of("error", "Şifre en az 4 karakter")); return;
        }
        try {
            User target = userService.getUserById(id).orElse(null);
            if (target == null) { ctx.status(404).json(Map.of("error", "Bulunamadı")); return; }
            if (target.getRole() == Role.ADMIN) {
                ctx.status(403).json(Map.of("error", "Admin değiştirilemez")); return;
            }
            target.setPasswordHash(org.mindrot.jbcrypt.BCrypt.hashpw(pwd,
                    org.mindrot.jbcrypt.BCrypt.gensalt()));
            userService.updateUser(target);
            ctx.json(Map.of("status", "ok"));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    /** DELETE /api/users/{id} */
    private void deleteUser(Context ctx) {
        User user = requireUser(ctx);
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.KASIYER) {
            ctx.status(403).json(Map.of("error", "Yetkisiz")); return;
        }
        Long id = Long.parseLong(ctx.pathParam("id"));
        try {
            userService.deleteUserById(id, user);
            ctx.json(Map.of("status", "deleted"));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    // ---- yardımcılar ----

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException ex) { return null; }
    }

    private static int toInt(Object v, int defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ex) { return defaultValue; }
    }

    // ============================================================
    //   Masa Rezervasyonu Endpoint'leri
    // ============================================================

    /** GET /api/reservations?date=YYYY-MM-DD — verilen günün rezervasyonları (varsayılan: bugün) */
    private void listReservations(Context ctx) {
        requireUser(ctx);
        String dateStr = ctx.queryParam("date");
        java.time.LocalDate date = (dateStr == null || dateStr.isBlank())
                ? java.time.LocalDate.now() : java.time.LocalDate.parse(dateStr);
        service.ReservationService svc = new service.ReservationService();
        java.util.List<model.Reservation> rows = svc.listForDate(date);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.Reservation r : rows) out.add(reservationJson(r));
        ctx.json(out);
    }

    /** GET /api/reservations/upcoming — masa kartı rozeti için yaklaşan kayıtlar */
    private void upcomingReservations(Context ctx) {
        requireUser(ctx);
        service.ReservationService svc = new service.ReservationService();
        java.util.List<model.Reservation> rows = svc.upcoming();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (model.Reservation r : rows) out.add(reservationJson(r));
        ctx.json(out);
    }

    /**
     * POST /api/reservations
     * Body: {tableNo, startTime, endTime, customerName, customerPhone, partySize, notes}
     * startTime / endTime ISO-LOCAL-DATETIME (örn. "2026-06-04T19:00")
     */
    @SuppressWarnings("unchecked")
    private void createReservation(Context ctx) {
        User user = requireUser(ctx);
        Map<String, Object> body;
        try {
            body = ctx.bodyAsClass(Map.class);
        } catch (Exception ex) {
            ctx.status(400).json(Map.of("error", "Geçersiz JSON: " + ex.getMessage()));
            return;
        }
        try {
            int tn = toInt(body.get("tableNo"), 0);
            java.time.LocalDateTime s = java.time.LocalDateTime.parse(String.valueOf(body.get("startTime")));
            java.time.LocalDateTime e = java.time.LocalDateTime.parse(String.valueOf(body.get("endTime")));
            String name = body.get("customerName") == null ? null : body.get("customerName").toString();
            String phone = body.get("customerPhone") == null ? null : body.get("customerPhone").toString();
            int party = toInt(body.get("partySize"), 1);
            String notes = body.get("notes") == null ? null : body.get("notes").toString();
            service.ReservationService svc = new service.ReservationService();
            model.Reservation r = svc.create(tn, s, e, name, phone, party, notes, user.getUsername());
            ctx.status(201).json(reservationJson(r));
        } catch (IllegalArgumentException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            ctx.status(409).json(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            ctx.status(500).json(Map.of("error", "Beklenmeyen hata: " + ex.getMessage()));
        }
    }

    private void cancelReservation(Context ctx) { changeReservationStatus(ctx, "cancel"); }
    private void seatReservation(Context ctx)   { changeReservationStatus(ctx, "seat"); }
    private void noShowReservation(Context ctx) { changeReservationStatus(ctx, "noshow"); }

    private void changeReservationStatus(Context ctx, String action) {
        requireUser(ctx);
        long id;
        try { id = Long.parseLong(ctx.pathParam("id")); }
        catch (NumberFormatException ex) { ctx.status(400).json(Map.of("error", "Geçersiz id")); return; }
        try {
            service.ReservationService svc = new service.ReservationService();
            switch (action) {
                case "cancel" -> svc.cancel(id);
                case "seat"   -> svc.markSeated(id);
                case "noshow" -> svc.markNoShow(id);
            }
            ctx.json(Map.of("status", "ok", "action", action, "id", id));
        } catch (RuntimeException ex) {
            ctx.status(400).json(Map.of("error", ex.getMessage()));
        }
    }

    private Map<String, Object> reservationJson(model.Reservation r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("tableNo", r.getTableNo());
        m.put("startTime", r.getStartTime() == null ? null : r.getStartTime().toString());
        m.put("endTime", r.getEndTime() == null ? null : r.getEndTime().toString());
        m.put("customerName", r.getCustomerName());
        m.put("customerPhone", r.getCustomerPhone());
        m.put("partySize", r.getPartySize());
        m.put("notes", r.getNotes());
        m.put("status", r.getStatus() == null ? "BOOKED" : r.getStatus().name());
        m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("createdBy", r.getCreatedBy());
        return m;
    }
}
