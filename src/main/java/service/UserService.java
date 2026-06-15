package service;

import DataConnection.Db;
import dao.UserDAO;
import dao.jdbc.UserJdbcDAO;
import model.Role;
import model.User;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    private final UserDAO userDAO;

    public UserService() {
        this(new UserJdbcDAO());
    }

    public UserService(UserDAO userDAO) {
        this.userDAO = Objects.requireNonNull(userDAO, "userDAO");
    }

    public Optional<User> getUserById(Long userId) {
        return userDAO.findById(userId);
    }

    public Optional<User> getUserByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    public List<User> getAllUsers() {
        return userDAO.findAll(0, Integer.MAX_VALUE);
    }

    /**
     * Kullanıcı adı + parola ile giriş.
     *
     * <p><b>Güvenlik:</b> Yalnız BCrypt hashleri kabul edilir. DB'de hâlâ
     * plaintext duran legacy kayıt varsa, bu metod onları KABUL ETMEZ.
     * O kayıtlar için admin {@link #changePassword(Long, String)} ile yeni
     * bir parola atamalıdır — bu sırada BCrypt hash otomatik üretilir.
     *
     * @return giriş başarılı ise User, başarısız ise null.
     */
    public User login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null) {
            return null;
        }
        Optional<User> opt = userDAO.findByUsername(username);
        if (opt.isEmpty()) {
            // Eşit süreli yanıt için boş BCrypt karşılaştırması yap (timing-attack azaltma)
            BCrypt.checkpw(rawPassword, "$2a$10$abcdefghijklmnopqrstuv12345678901234567890123456789012");
            return null;
        }

        User user = opt.get();
        String hash = user.getPasswordHash();

        if (hash == null || !hash.startsWith("$2")) {
            LOG.warn("Plaintext veya geçersiz parola hash'i: user={}. Admin yeni parola atamalı.",
                    user.getUsername());
            return null;
        }

        boolean ok;
        try {
            ok = BCrypt.checkpw(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            // Bozuk hash formatı
            LOG.warn("Bozuk BCrypt hash: user={}", user.getUsername());
            return null;
        }

        if (!ok || !user.isActive()) return null;
        return user;
    }

    public Long createUser(String username, String rawPassword, Role role, String fullName) {
        String safeFullName = (fullName == null || fullName.isBlank()) ? username : fullName;
        String hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        User user = new User(username, hash, role, safeFullName);
        return userDAO.create(user);
    }

    public void updateUser(User user) {
        userDAO.update(user);
    }

    public void deleteUser(Long userId) {
        deleteUserById(userId, null);
    }

    public void deleteUserById(Long userId, User actor) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Geçersiz kullanıcı ID");
        }
        if (actor != null && actor.getId() != null && actor.getId().equals(userId)) {
            throw new IllegalStateException("Kendi hesabınızı silemezsiniz");
        }
        User target = userDAO.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Kullanıcı bulunamadı"));
        if (target.getRole() == Role.ADMIN) {
            long adminCount = userDAO.countByRole(Role.ADMIN);
            if (adminCount <= 1) {
                throw new IllegalStateException("Sistemde en az bir yönetici kalmalıdır");
            }
        }
        // FK temizliği: orders.waiter_id ve payments.cashier_id NULL'a setlenir,
        // böylece kullanıcı silinebilir; tarihsel kayıtlar (sipariş/ödeme)
        // korunur, sadece referans kaybolur.
        try {
            detachReferences(userId);
            userDAO.deleteById(userId);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(resolveDeleteError(ex), ex);
        }
    }

    /**
     * Bir kullanıcıyı silmeden önce, FK referanslarını (orders.waiter_id,
     * payments.cashier_id, user_area_permissions.user_id) tek tek temizler.
     * Tarihsel veriyi (sipariş/ödeme satırları) silmez; sadece bağlantıyı koparır.
     */
    private void detachReferences(long userId) {
        // FK_CHECKS=0 yerine TEK TEK update — daha güvenli, transaction içinde
        Db.tx(conn -> {
            executeUpdate(conn, "UPDATE orders   SET waiter_id  = NULL WHERE waiter_id  = ?", userId);
            executeUpdate(conn, "UPDATE payments SET cashier_id = NULL WHERE cashier_id = ?", userId);
            // user_area_permissions ON DELETE CASCADE → otomatik silinir
            return null;
        });
    }

    private void executeUpdate(Connection conn, String sql, long id) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Eski şemada cashier_id sütunu yoksa veya tablo yoksa sessiz geç
            LOG.warn("FK temizleme sırasında uyarı ({}): {}", sql, e.getMessage());
        }
    }

    public void setUserRole(Long userId, Role role) {
        userDAO.updateRole(userId, role);
    }

    public void activate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(true);
            userDAO.update(u);
        });
    }

    public void deactivate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(false);
            userDAO.update(u);
        });
    }

    public void changePassword(Long userId, String newRawPassword) {
        String newHash = BCrypt.hashpw(newRawPassword, BCrypt.gensalt());
        userDAO.findById(userId).ifPresent(u -> {
            u.setPasswordHash(newHash);
            userDAO.update(u);
            // Denetim log'u — şifre veya hash YAZILMAZ, sadece kim/kime.
            service.audit.AuditLog.passwordChanged("(system)", u.getUsername());
        });
    }

    private String resolveDeleteError(RuntimeException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase();
            if (normalized.contains("foreign") && normalized.contains("key")) {
                return "Kullanıcı silinemedi. İlişkili kayıtlar mevcut olabilir.";
            }
        }
        return "Kullanıcı silinemedi";
    }
    /**
     * Uygulama başlarken çağır: admin kullanıcısı yoksa oluşturur.
     * @return true => oluşturuldu, false => zaten vardı
     */
    public boolean seedAdminIfNotExists() {
        Optional<User> existing = userDAO.findByUsername("admin");
        if (existing.isPresent()) {
            return false; // zaten var
        }

        // Seed parolası kaynağı:
        //   1) BUDGET_ADMIN_SEED_PASSWORD env değişkeni (üretim için ZORUNLU)
        //   2) Geliştirme modunda (BUDGET_ENV != production) — rastgele 16 karakter
        //      üretilir, KONSOLA bir kez basılır, log dosyasına YAZILMAZ.
        //   3) Üretim modunda env yoksa boot DURDURULUR.
        String seedPassword = System.getenv("BUDGET_ADMIN_SEED_PASSWORD");
        boolean fromEnv = (seedPassword != null && !seedPassword.isBlank());
        boolean production = "production".equalsIgnoreCase(
                System.getenv().getOrDefault("BUDGET_ENV", System.getProperty("budget.env", "dev")));

        if (!fromEnv) {
            if (production) {
                String msg = "Üretim modunda admin seed parolası gerekli ama BUDGET_ADMIN_SEED_PASSWORD set edilmemiş. " +
                        "Uygulamayı başlatmadan önce env değişkenini ayarlayın.";
                LOG.error(msg);
                throw new IllegalStateException(msg);
            }
            // Geliştirme modu: rastgele güçlü bir parola üret.
            seedPassword = generateRandomPassword(16);
            // !! KONSOLA bas (log dosyasına yazma — sout doğrudan stderr/stdout) !!
            // Parolayı LOGGER ile YAZMA — sadece System.out kullan.
            System.out.println("================================================================");
            System.out.println("  İlk kurulum: admin kullanıcısı oluşturuluyor.");
            System.out.println("  Geçici parola (yalnız BU EKRANDA gösterilir):");
            System.out.println();
            System.out.println("      kullanıcı: admin");
            System.out.println("      parola:   " + seedPassword);
            System.out.println();
            System.out.println("  TODO: İlk girişten sonra DERHAL parolayı değiştirin.");
            System.out.println("  (Bu parola log dosyalarına YAZILMADI.)");
            System.out.println("================================================================");
        }
        // Hash'li yaz; ham parolayı log'a sızdırma.
        createUser("admin", seedPassword, Role.ADMIN, "Admin User");
        // Hassas referansı GC'ye bırak (Java String immutable, ama referansı düşür).
        // noinspection UnusedAssignment
        seedPassword = null;
        LOG.info("İlk admin kullanıcısı oluşturuldu. (kullanıcı=admin, parola=<gizli>)");
        return true;
    }

    /** Geliştirme modu için kriptografik güçte rastgele parola üretir. */
    private static String generateRandomPassword(int length) {
        // Karışıklığı önlemek için 0/O, 1/l/I ayıklanmış alfabe
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*";
        java.security.SecureRandom sr = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(sr.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /**
     * (İsteğe bağlı) Gerekirse açıkça çağırabileceğin kısa yardımcı.
     */
    public Long createAdmin(String rawPassword) {
        return createUser("admin", rawPassword, Role.ADMIN, "Admin User");
    }

}
