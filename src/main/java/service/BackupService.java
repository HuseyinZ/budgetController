package service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Periyodik MySQL yedek servisi.
 *
 * <p>Saatte bir (varsayılan) {@code mysqldump} çalıştırır, çıktıyı
 * {@code ~/Backups/budget-YYYYMMDD-HHmmss.sql} dosyasına yazar.
 *
 * <p>30 günden eski yedekleri otomatik temizler (rolling retention).
 *
 * <p><b>Önemli:</b> {@code mysqldump} sistem PATH'inde bulunmalı. Windows'ta
 * varsayılan kurulum yolu:
 * <pre>C:\Program Files\MySQL\MySQL Server 9.3\bin\mysqldump.exe</pre>
 *
 * <p>db.properties'ten okur:
 * <ul>
 *   <li>db.url   → host, port, dbname çıkarımı</li>
 *   <li>db.user  → MySQL kullanıcısı</li>
 *   <li>db.password → MySQL şifresi (geçici env değişkenine yazılır)</li>
 * </ul>
 */
public class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "jdbc:mysql://([^:/]+)(?::(\\d+))?/([^?]+).*");
    /** Eski yedekleri sil — bu sürenin altındakini koru. */
    private static final long RETENTION_DAYS = 30;

    private final Path backupDir;
    private final String host;
    private final int port;
    private final String dbName;
    private final String user;
    private final String password;
    private final String mysqldumpCmd;
    private ScheduledExecutorService scheduler;

    public BackupService() {
        Properties p = loadProps();
        String url = p.getProperty("db.url", "jdbc:mysql://localhost:3306/posdb");
        Matcher m = URL_PATTERN.matcher(url);
        if (m.matches()) {
            this.host = m.group(1);
            this.port = m.group(2) == null ? 3306 : Integer.parseInt(m.group(2));
            this.dbName = m.group(3);
        } else {
            LOG.warn("db.url ayrıştırılamadı: {} — yedek devre dışı", url);
            this.host = "localhost";
            this.port = 3306;
            this.dbName = "posdb";
        }
        this.user = p.getProperty("db.user", "root");
        this.password = p.getProperty("db.password", "");
        this.mysqldumpCmd = resolveMysqlDumpCommand();
        this.backupDir = Paths.get(System.getProperty("user.home"), "Backups");
    }

    /** Periyodik yedek başlatır (uygulama başlangıcında çağrılır). */
    public synchronized void startScheduler(long initialDelayMinutes, long periodMinutes) {
        if (scheduler != null) return; // zaten çalışıyor
        try {
            Files.createDirectories(backupDir);
        } catch (IOException ex) {
            LOG.error("Yedek klasörü oluşturulamadı: {} - {}", backupDir, ex.getMessage());
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backup-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::safeBackup,
                initialDelayMinutes, periodMinutes, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::shutdown, "backup-shutdown"));
        LOG.info("Otomatik yedek aktif: her {} dakikada bir → {}", periodMinutes, backupDir);
    }

    public synchronized void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Manuel tetikleme — admin "Şimdi yedekle" butonundan çağırabilir. */
    public boolean backupNow() {
        return doBackup();
    }

    private void safeBackup() {
        try {
            boolean ok = doBackup();
            if (ok) cleanOldBackups();
        } catch (RuntimeException ex) {
            LOG.warn("Yedek görevi hata: {}", ex.getMessage());
        }
    }

    /** Asıl dump işlemi. true = başarılı. */
    private boolean doBackup() {
        if (mysqldumpCmd == null) {
            LOG.warn("mysqldump bulunamadı — yedek atlandı");
            return false;
        }
        String stamp = LocalDateTime.now().format(STAMP);
        // BACKUP_PASS varsa şifreli yedek (.sql.enc), yoksa düz .sql
        String backupPass = System.getenv("BACKUP_PASS");
        boolean encrypt = backupPass != null && !backupPass.isBlank();
        // Production'da şifresiz yedek izni: BACKUP_ALLOW_PLAINTEXT=true gerek
        boolean allowPlain = Boolean.parseBoolean(
                System.getenv().getOrDefault("BACKUP_ALLOW_PLAINTEXT", "true"));
        if (!encrypt && !allowPlain) {
            LOG.warn("Yedek atlandı: BACKUP_PASS yok ve BACKUP_ALLOW_PLAINTEXT=false");
            return false;
        }

        Path target = backupDir.resolve("budget-" + stamp + ".sql");
        ProcessBuilder pb = new ProcessBuilder(
                mysqldumpCmd,
                "--host=" + host,
                "--port=" + port,
                "--user=" + user,
                "--default-character-set=utf8mb4",
                "--single-transaction",
                "--routines",
                "--triggers",
                "--result-file=" + target.toString(),
                dbName);
        // Şifreyi env üzerinden geç (-p flag kullanmaktan daha güvenli)
        pb.environment().put("MYSQL_PWD", password == null ? "" : password);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(2, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                LOG.warn("mysqldump zaman aşımına uğradı (2dk)");
                return false;
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                LOG.warn("mysqldump hatası (exit={}) — dosya: {}", exit, target);
                return false;
            }
            long size = Files.size(target);
            // İsteğe bağlı şifreleme — AES-256-GCM
            if (encrypt) {
                Path encrypted = backupDir.resolve("budget-" + stamp + ".sql.enc");
                try {
                    encryptFile(target, encrypted, backupPass);
                    Files.deleteIfExists(target);   // düz dosyayı silelim
                    long encSize = Files.size(encrypted);
                    LOG.info("Şifreli yedek alındı: {} ({} byte, kaynak {} byte)",
                            encrypted.getFileName(), encSize, size);
                    service.audit.AuditLog.backupCreated(
                            encrypted.getFileName().toString(), encSize, true);
                } catch (Exception ex) {
                    LOG.error("Yedek şifrelenemedi: {}", ex.getMessage());
                    return false;
                }
            } else {
                LOG.info("Yedek alındı (şifresiz): {} ({} byte)", target.getFileName(), size);
                service.audit.AuditLog.backupCreated(
                        target.getFileName().toString(), size, false);
            }
            return true;
        } catch (IOException | InterruptedException ex) {
            LOG.warn("Yedek alınamadı: {}", ex.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * AES-256-GCM ile dosya şifreleme.
     *
     * <p>Format: 16 byte salt | 12 byte IV | ciphertext (GCM tag dahil).
     * Anahtar PBKDF2-HMAC-SHA256(passphrase, salt, 600 000 iter, 256 bit).
     *
     * <p>İterasyon sayısı OWASP 2023 Password Storage Cheat Sheet'in PBKDF2-HMAC-SHA256
     * için önerdiği 600 000 değerinde. Günde 1 yedek için CPU maliyeti < 200 ms,
     * brute-force maliyeti ~10x artar.
     *
     * <p>Çözmek için: bkz. SECURITY.md §4 (Java tek satır komutu). Restore aracı
     * aynı iterasyon sayısını kullanmalı; aksi halde decrypt başarısız olur.
     */
    private static final int PBKDF2_ITERATIONS = 600_000;

    private static void encryptFile(Path in, Path out, String passphrase) throws Exception {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);

        javax.crypto.spec.PBEKeySpec spec =
                new javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        javax.crypto.SecretKey tmp = javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256").generateSecret(spec);
        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(tmp.getEncoded(), "AES");

        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec gcm = new javax.crypto.spec.GCMParameterSpec(128, iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, gcm);

        try (java.io.OutputStream os = Files.newOutputStream(out);
             java.io.InputStream is = Files.newInputStream(in);
             javax.crypto.CipherOutputStream cos = new javax.crypto.CipherOutputStream(os, cipher)) {
            os.write(salt);
            os.write(iv);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) cos.write(buf, 0, n);
        }
    }

    /** RETENTION_DAYS'ten eski yedekleri sil. */
    private void cleanOldBackups() {
        long cutoffMillis = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(RETENTION_DAYS);
        try (Stream<Path> files = Files.list(backupDir)) {
            List<Path> oldOnes = files
                    .filter(p -> p.getFileName().toString().startsWith("budget-"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".sql") || name.endsWith(".sql.enc");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoffMillis;
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
            for (Path p : oldOnes) {
                try {
                    Files.delete(p);
                    LOG.info("Eski yedek silindi: {}", p.getFileName());
                } catch (IOException ex) {
                    LOG.warn("Eski yedek silinemedi: {} - {}", p, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            LOG.warn("Yedek klasörü taranamadı: {}", ex.getMessage());
        }
    }

    /**
     * mysqldump komutunu çözer:
     * 1. Önce sistem PATH'inde dene
     * 2. Bulunmazsa Windows'ta varsayılan kurulum yolunu dene
     */
    private String resolveMysqlDumpCommand() {
        String cmd = isWindows() ? "mysqldump.exe" : "mysqldump";
        if (commandExists(cmd)) return cmd;
        if (isWindows()) {
            String[] candidates = {
                    "C:\\Program Files\\MySQL\\MySQL Server 9.3\\bin\\mysqldump.exe",
                    "C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysqldump.exe",
                    "C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysqldump.exe",
            };
            for (String c : candidates) {
                if (Files.exists(Paths.get(c))) return c;
            }
        }
        return null;
    }

    private boolean commandExists(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
            Process proc = pb.start();
            return proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (IOException ex) {
            // Komut PATH'te yok — interrupt flag SET ETME (yan thread'leri etkiler)
            return false;
        } catch (InterruptedException ex) {
            // Gerçek interrupt — bayrağı koru
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private Properties loadProps() {
        Properties p = new Properties();
        // 1. Kullanıcı override
        Path userFile = Paths.get(System.getProperty("user.home"), ".budget", "db.properties");
        if (Files.exists(userFile)) {
            try (var in = Files.newInputStream(userFile)) {
                p.load(in);
                return p;
            } catch (IOException ignore) {}
        }
        // 2. Classpath default
        try (var in = BackupService.class.getResourceAsStream("/db.properties")) {
            if (in != null) p.load(in);
        } catch (IOException ignore) {}
        return p;
    }
}
