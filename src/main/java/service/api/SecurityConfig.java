package service.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API güvenlik yapılandırması — env / system property üzerinden.
 *
 * <p>Tüm değerler ya {@code -Dkey=value} ile ya da uppercase env değişkeniyle
 * (örn. {@code API_ALLOWED_ORIGINS}) sağlanabilir.
 *
 * <p><b>UYARI:</b> CORS, kimlik doğrulama/yetkilendirme YERİNE GEÇMEZ.
 * Tarayıcı tarafından zorlanan bir politikadır. Asıl güvenlik
 * {@code SessionStore} + role kontrolleridir.
 */
public final class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private SecurityConfig() {}

    /**
     * İzinli CORS origin'leri. Varsayılan: tüm localhost varyantları (yerel geliştirme).
     * Üretimde {@code API_ALLOWED_ORIGINS=https://x.tailscale.net,https://posbilg.com}
     * gibi virgülle ayrılmış allowlist verilir.
     *
     * <p>NOT: Browser Origin header'ı tam eşleşmeli (scheme + host + port).
     * Yerel geliştirme için en yaygın port'ları (7070, 7443, ve port'suz)
     * default'a ekledik ki "Method GET 400" / preflight hatası çıkmasın.
     */
    public static List<String> allowedOrigins() {
        String raw = resolve("api.allowed.origins", "API_ALLOWED_ORIGINS",
                "http://localhost,http://localhost:7070,http://localhost:7443,"
                + "http://127.0.0.1,http://127.0.0.1:7070,http://127.0.0.1:7443");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    /** API bind adresi. Üretimde {@code 127.0.0.1} veya Tailscale IP olmalı. */
    public static String bindAddress() {
        return resolve("api.bind", "API_BIND_ADDRESS", "0.0.0.0");
    }

    /** HTTPS aktif mi? */
    public static boolean httpsEnabled() {
        return Boolean.parseBoolean(resolve("api.https.enabled", "API_HTTPS_ENABLED", "false"));
    }

    /** HTTP aktif mi? Üretimde {@code false} olmalı. */
    public static boolean httpEnabled() {
        return Boolean.parseBoolean(resolve("api.http.enabled", "API_HTTP_ENABLED", "true"));
    }

    public static int httpPort() {
        return Integer.parseInt(resolve("api.port", "API_PORT", "7070"));
    }

    public static int httpsPort() {
        return Integer.parseInt(resolve("api.https.port", "API_HTTPS_PORT", "7443"));
    }

    /** Login dakikada maksimum deneme (her IP için). */
    public static int loginRatePerMinute() {
        return Integer.parseInt(resolve("api.login.rate.perMin", "API_LOGIN_RATE_PER_MIN", "20"));
    }

    /** Max request body boyutu (byte). */
    public static long maxBodyBytes() {
        return Long.parseLong(resolve("api.maxBodyBytes", "API_MAX_BODY_BYTES", "262144")); // 256 KB
    }

    /** Sayfa varsayılan limit. */
    public static int defaultPageLimit() {
        return Integer.parseInt(resolve("api.page.default", "API_PAGE_DEFAULT", "50"));
    }

    /** Sayfa maksimum limit. */
    public static int maxPageLimit() {
        return Integer.parseInt(resolve("api.page.max", "API_PAGE_MAX", "500"));
    }

    /** Üretim modu mu? */
    public static boolean isProduction() {
        String env = System.getenv().getOrDefault("BUDGET_ENV", System.getProperty("budget.env", "dev"));
        return "production".equalsIgnoreCase(env);
    }

    private static String resolve(String sysKey, String envKey, String def) {
        String sys = System.getProperty(sysKey);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) return env;
        return def;
    }

    /**
     * Güvenilen proxy IP'leri (CIDR formatı desteklenmiyor — şimdilik
     * sadece tam IP eşleşmesi). {@code X-Forwarded-For} yalnız bu
     * IP'lerden gelen istekler için okunur; aksi halde spoof edilebilir.
     *
     * <p>Varsayılan: {@code 127.0.0.1,::1} (local reverse proxy senaryosu).
     * Tailscale Serve / Nginx farklı IP'de ise burada belirtin.
     */
    public static java.util.Set<String> trustedProxyIps() {
        String raw = resolve("api.trusted.proxies", "API_TRUSTED_PROXIES",
                "127.0.0.1,::1");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** İzinli origin'lerin sade görünümü (boş ise tüm origin'ler reddedilir). */
    public static List<String> allowedOriginsOrEmpty() {
        try {
            return allowedOrigins();
        } catch (Exception ex) {
            LOG.warn("Allowed origins okunamadı: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }
}
