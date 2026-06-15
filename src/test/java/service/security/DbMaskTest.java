package service.security;

import DataConnection.Db;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbMaskTest {

    @Test void maskUrlSecretsHidesPassword() {
        String in = "jdbc:mysql://localhost:3306/?user=root&password=secret123";
        String out = Db.maskUrlSecrets(in);
        assertFalse(out.contains("secret123"), "Şifre asla görünmemeli");
        assertTrue(out.contains("password=****"));
        assertTrue(out.contains("user=****"));
    }

    @Test void maskUrlSecretsHandlesUppercase() {
        String in = "jdbc:mysql://x/?PASSWORD=Sw0rd&User=admin";
        String out = Db.maskUrlSecrets(in);
        assertFalse(out.toLowerCase().contains("sw0rd"));
        assertFalse(out.toLowerCase().contains("admin"));
    }

    @Test void maskUrlSecretsKeepsNonSensitive() {
        String in = "jdbc:mysql://localhost:3306/posdb?useUnicode=true&serverTimezone=UTC";
        String out = Db.maskUrlSecrets(in);
        assertEquals(in, out, "Şifre/user yoksa URL değişmemeli");
    }

    @Test void maskUrlSecretsNullAndEmpty() {
        assertEquals("", Db.maskUrlSecrets(""));
        // Db.maskUrlSecrets(null) güvenli olarak boş string döner — koruyucu
        assertEquals("", Db.maskUrlSecrets(null));
    }
}
