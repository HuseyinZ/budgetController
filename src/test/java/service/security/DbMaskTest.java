package service.security;

import org.junit.jupiter.api.Test;
import service.util.Mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL secret maskeleme testleri — hedef: {@link Mask#urlSecrets(String)}.
 *
 * <p>Bilinçli olarak {@code DataConnection.Db}'ye referans YOKTUR: Db'nin
 * static initializer'ı gerçek MySQL bağlantısı kurar ve CI runner'ında
 * ExceptionInInitializerError üretir. Db.maskUrlSecrets zaten Mask'e delege eder.
 */
class DbMaskTest {

    @Test void maskUrlSecretsHidesPassword() {
        String in = "jdbc:mysql://localhost:3306/?user=root&password=secret123";
        String out = Mask.urlSecrets(in);
        assertFalse(out.contains("secret123"), "Şifre asla görünmemeli");
        assertTrue(out.contains("password=****"));
        assertTrue(out.contains("user=****"));
    }

    @Test void maskUrlSecretsHandlesUppercase() {
        String in = "jdbc:mysql://x/?PASSWORD=Sw0rd&User=admin";
        String out = Mask.urlSecrets(in);
        assertFalse(out.toLowerCase().contains("sw0rd"));
        assertFalse(out.toLowerCase().contains("admin"));
    }

    @Test void maskUrlSecretsKeepsNonSensitive() {
        String in = "jdbc:mysql://localhost:3306/posdb?useUnicode=true&serverTimezone=UTC";
        String out = Mask.urlSecrets(in);
        assertEquals(in, out, "Şifre/user yoksa URL değişmemeli");
    }

    @Test void maskUrlSecretsNullAndEmpty() {
        assertEquals("", Mask.urlSecrets(""));
        // Mask.urlSecrets(null) güvenli olarak boş string döner — koruyucu
        assertEquals("", Mask.urlSecrets(null));
    }
}
