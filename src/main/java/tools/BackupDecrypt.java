package tools;

import java.io.Console;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Şifreli yedek (.sql.enc) çözücü CLI.
 *
 * <p>{@link service.BackupService#encryptFile} ile aynı format:
 * <pre>16 byte salt | 12 byte IV | ciphertext (AES-256-GCM, 128-bit tag)</pre>
 * Anahtar: PBKDF2-HMAC-SHA256(passphrase, salt, 600 000 iter, 256-bit).
 *
 * <p><b>Kullanım:</b>
 * <pre>
 * # Çevre değişkeniyle parola (önerilen — bash history'de görünmez)
 * BACKUP_PASS=...&lt;parola&gt;... \
 *     java -cp target/budgetController-*.jar tools.BackupDecrypt \
 *          budget-20260101-120000.sql.enc restored.sql
 *
 * # Veya parola interaktif sorulur
 * java -cp target/budgetController-*.jar tools.BackupDecrypt \
 *      budget-20260101-120000.sql.enc restored.sql
 * </pre>
 *
 * <p><b>Sonra restore:</b>
 * <pre>mysql -u root -p posdb &lt; restored.sql</pre>
 *
 * <p><b>Güvenlik notu:</b> Çözülen {@code .sql} dosyası DÜZ METİN'dir; restore
 * sonrası mutlaka silin (Windows: {@code del /F restored.sql}).
 */
public final class BackupDecrypt {

    private static final int PBKDF2_ITERATIONS = 600_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Kullanım: BackupDecrypt <kaynak.sql.enc> <hedef.sql>");
            System.exit(1);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        if (!Files.exists(in)) {
            System.err.println("Kaynak yok: " + in);
            System.exit(1);
        }
        if (Files.exists(out)) {
            System.err.println("Hedef zaten var (üzerine yazmıyoruz): " + out);
            System.exit(1);
        }

        // Parola: önce env, sonra interaktif
        String pass = System.getenv("BACKUP_PASS");
        if (pass == null || pass.isBlank()) {
            Console c = System.console();
            if (c == null) {
                System.err.println("Parola için ya BACKUP_PASS env değişkenini ayarlayın "
                        + "ya da TTY'den çalıştırın.");
                System.exit(2);
            }
            char[] arr = c.readPassword("BACKUP_PASS: ");
            if (arr == null || arr.length == 0) {
                System.err.println("Parola boş olamaz");
                System.exit(2);
            }
            pass = new String(arr);
        }

        try {
            decrypt(in, out, pass);
            System.out.println("OK: " + out + " (" + Files.size(out) + " byte)");
            System.out.println("UYARI: Restore sonrası bu düz metin dosyayı silin.");
        } catch (Exception ex) {
            // Yarım kalmış dosyayı sil
            Files.deleteIfExists(out);
            System.err.println("Çözme hatası: " + ex.getMessage());
            System.exit(3);
        }
    }

    /**
     * Verilen şifreli dosyayı çözüp düz {@code .sql} olarak yazar.
     * Yanlış parola → {@link javax.crypto.AEADBadTagException} fırlar (GCM tag uyuşmaz).
     */
    public static void decrypt(Path in, Path out, String passphrase) throws Exception {
        try (InputStream is = Files.newInputStream(in);
             OutputStream os = Files.newOutputStream(out)) {

            byte[] salt = readN(is, 16);
            byte[] iv = readN(is, 12);

            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            SecretKey tmp = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec);
            SecretKey key = new SecretKeySpec(tmp.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

            try (CipherInputStream cis = new CipherInputStream(is, cipher)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = cis.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
        }
    }

    private static byte[] readN(InputStream is, int n) throws Exception {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = is.read(buf, read, n - read);
            if (r < 0) throw new java.io.EOFException("Dosya çok kısa: header okunamadı");
            read += r;
        }
        return buf;
    }
}
