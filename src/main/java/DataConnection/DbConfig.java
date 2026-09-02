package DataConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Veritabanı bağlantı yapılandırması — DB'ye dokunmayan saf çözümleme.
 *
 * <p>Öncelik sırası (yüksekten düşüğe):
 * <ol>
 *   <li>Sistem özelliği: {@code -Ddb.url / -Ddb.user / -Ddb.password}</li>
 *   <li>Ortam değişkeni: {@code DB_URL / DB_USER / DB_PASS}</li>
 *   <li>Kullanıcı config dosyası: {@code ~/.budget/db.properties}</li>
 * </ol>
 *
 * <p>Classpath'teki {@code db.properties} bilinçli olarak kaynak DEĞİLDİR
 * (JAR'a paketlenebilecek dosya credential taşımamalı; pom.xml de dışlar).
 *
 * <p><b>Güvenlik:</b> URL, kullanıcı adı veya parola için gömülü varsayılan
 * YOKTUR. Üçünden biri eksikse {@link MissingConfigException} fırlatılır —
 * uygulama sessizce {@code root} gibi bir hesaba düşmez. Hata mesajı yalnız
 * eksik ANAHTAR adlarını içerir, değer içermez.
 *
 * <p>Havuz ayarları ({@code db.pool.maxSize}, {@code db.pool.minIdle})
 * credential olmadığından güvenli varsayılanlarla gelir.
 */
public final class DbConfig {

    public static final String KEY_URL = "db.url";
    public static final String KEY_USER = "db.user";
    public static final String KEY_PASSWORD = "db.password";
    public static final String KEY_POOL_MAX = "db.pool.maxSize";
    public static final String KEY_POOL_MIN_IDLE = "db.pool.minIdle";

    public static final String ENV_URL = "DB_URL";
    public static final String ENV_USER = "DB_USER";
    public static final String ENV_PASSWORD = "DB_PASS";
    public static final String ENV_POOL_MAX = "DB_POOL_MAX";
    public static final String ENV_POOL_MIN_IDLE = "DB_POOL_MIN_IDLE";

    /** Migration (DDL yetkili, örn. budget_migrate) kimliği — runtime kimliğinden AYRI. */
    public static final String KEY_MIGRATE_USER = "db.migrate.user";
    public static final String KEY_MIGRATE_PASSWORD = "db.migrate.password";
    public static final String ENV_MIGRATE_USER = "DB_MIGRATE_USER";
    public static final String ENV_MIGRATE_PASSWORD = "DB_MIGRATE_PASS";

    /**
     * Hangi kimlik isteniyor?
     * <ul>
     *   <li>{@link #RUNTIME}: uygulama ({@code db.user/db.password}, budget_app).</li>
     *   <li>{@link #MIGRATION}: şema değişikliği ({@code db.migrate.user/password}) — açıkça
     *       verilmek ZORUNDADIR; runtime kimliğine ASLA sessizce düşülmez. URL ortaktır.</li>
     * </ul>
     */
    public enum Role { RUNTIME, MIGRATION }

    private static final String DEFAULT_POOL_MAX = "10";
    private static final String DEFAULT_POOL_MIN_IDLE = "2";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int maxPoolSize;
    private final int minIdle;

    private DbConfig(String jdbcUrl, String username, String password, int maxPoolSize, int minIdle) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maxPoolSize = maxPoolSize;
        this.minIdle = minIdle;
    }

    /**
     * Yapılandırmayı çözer. Test edilebilirlik için kaynaklar parametre olarak alınır.
     *
     * @param fileProps classpath + kullanıcı dosyasından yüklenmiş özellikler (null → boş)
     * @param sysProps  sistem özelliği okuyucu (üretimde {@code System::getProperty})
     * @param env       ortam değişkeni okuyucu (üretimde {@code System::getenv})
     * @throws MissingConfigException url / user / password'den biri bulunamazsa
     */
    public static DbConfig load(Properties fileProps,
                                Function<String, String> sysProps,
                                Function<String, String> env) {
        return loadFor(Role.RUNTIME, fileProps, sysProps, env);
    }

    /**
     * Rol bazlı çözümleme — öncelik ve "varsayılan yok" kuralı TEK yerde.
     *
     * <p>{@link Role#MIGRATION}: önce runtime yapılandırması (URL dahil) doğrulanır,
     * sonra migrate kullanıcı/parola aynı öncelikle çözülür; ikisi de zorunludur.
     * Dönen nesnede {@code username/password} migrate kimliğidir, URL ve havuz
     * ayarları runtime ile ortaktır.
     *
     * @throws MissingConfigException eksik anahtarlar (yalnız ANAHTAR adları) ile
     */
    public static DbConfig loadFor(Role role, Properties fileProps,
                                   Function<String, String> sysProps,
                                   Function<String, String> env) {
        Objects.requireNonNull(role, "role");
        Properties props = fileProps == null ? new Properties() : fileProps;
        Objects.requireNonNull(sysProps, "sysProps");
        Objects.requireNonNull(env, "env");

        String url = resolve(KEY_URL, ENV_URL, props, sysProps, env);
        String user = resolve(KEY_USER, ENV_USER, props, sysProps, env);
        String password = resolve(KEY_PASSWORD, ENV_PASSWORD, props, sysProps, env);

        List<String> missing = new ArrayList<>();
        if (url == null) missing.add(KEY_URL);
        if (user == null) missing.add(KEY_USER);
        if (password == null) missing.add(KEY_PASSWORD);
        if (!missing.isEmpty()) {
            throw new MissingConfigException(missing);
        }

        if (role == Role.MIGRATION) {
            String migUser = resolve(KEY_MIGRATE_USER, ENV_MIGRATE_USER, props, sysProps, env);
            String migPassword = resolve(KEY_MIGRATE_PASSWORD, ENV_MIGRATE_PASSWORD, props, sysProps, env);
            List<String> missingMig = new ArrayList<>();
            if (migUser == null) missingMig.add(KEY_MIGRATE_USER);
            if (migPassword == null) missingMig.add(KEY_MIGRATE_PASSWORD);
            if (!missingMig.isEmpty()) {
                throw new MissingConfigException(missingMig); // runtime kimliğine düşülmez
            }
            user = migUser;
            password = migPassword;
        }

        int maxPool = parsePositiveInt(
                resolveOrDefault(KEY_POOL_MAX, ENV_POOL_MAX, props, sysProps, env, DEFAULT_POOL_MAX),
                KEY_POOL_MAX);
        int minIdle = parsePositiveInt(
                resolveOrDefault(KEY_POOL_MIN_IDLE, ENV_POOL_MIN_IDLE, props, sysProps, env, DEFAULT_POOL_MIN_IDLE),
                KEY_POOL_MIN_IDLE);
        return new DbConfig(url, user, password, maxPool, Math.min(minIdle, maxPool));
    }

    public String jdbcUrl()   { return jdbcUrl; }
    public String username()  { return username; }
    public String password()  { return password; }
    public int maxPoolSize()  { return maxPoolSize; }
    public int minIdle()      { return minIdle; }

    /** Log/debug için güvenli temsil — parola ve URL içindeki secret'lar maskeli. */
    @Override
    public String toString() {
        return "DbConfig{url=" + service.util.Mask.urlSecrets(jdbcUrl)
                + ", user=" + service.util.Mask.user(username)
                + ", password=****, maxPool=" + maxPoolSize + ", minIdle=" + minIdle + "}";
    }

    // ------------------------------------------------------------------

    /** sys > env > file; hepsi boşsa null (varsayılan YOK). */
    private static String resolve(String propertyKey, String envKey, Properties props,
                                  Function<String, String> sysProps, Function<String, String> env) {
        String sys = sysProps.apply(propertyKey);
        if (sys != null && !sys.isBlank()) return sys.trim();
        String e = env.apply(envKey);
        if (e != null && !e.isBlank()) return e.trim();
        String fromProps = props.getProperty(propertyKey);
        if (fromProps != null && !fromProps.isBlank()) return fromProps.trim();
        return null;
    }

    private static String resolveOrDefault(String propertyKey, String envKey, Properties props,
                                           Function<String, String> sysProps, Function<String, String> env,
                                           String defaultValue) {
        String v = resolve(propertyKey, envKey, props, sysProps, env);
        return v == null ? defaultValue : v;
    }

    private static int parsePositiveInt(String raw, String key) {
        try {
            int v = Integer.parseInt(raw);
            if (v <= 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Geçersiz havuz ayarı: " + key + " pozitif tam sayı olmalı");
        }
    }

    /**
     * Zorunlu DB yapılandırması eksik. Mesaj yalnız eksik anahtar adlarını ve
     * beklenen kaynakları listeler — hiçbir değer içermez.
     */
    public static final class MissingConfigException extends IllegalStateException {
        private final List<String> missingKeys;

        public MissingConfigException(List<String> missingKeys) {
            super("Veritabanı yapılandırması eksik: " + String.join(", ", missingKeys)
                    + ". Beklenen kaynaklardan biri: sistem özellikleri (-Ddb.url/-Ddb.user/-Ddb.password), "
                    + "ortam değişkenleri (DB_URL/DB_USER/DB_PASS) veya ~/.budget/db.properties. "
                    + "Gömülü varsayılan hesap yoktur.");
            this.missingKeys = List.copyOf(missingKeys);
        }

        public List<String> missingKeys() { return missingKeys; }
    }
}
