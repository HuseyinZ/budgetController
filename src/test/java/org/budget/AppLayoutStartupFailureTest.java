package org.budget;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Masa düzeni açılışta geçersizse uygulamanın nasıl davrandığını sabitler.
 *
 * <p>Kontrol kaynak düzeyinde yapılır: ilgili yol {@code System.exit} çağırır
 * ve Swing diyaloğu açar; headless test JVM'inde çalıştırılamaz.
 */
class AppLayoutStartupFailureTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "org", "budget", "App.java");

    private static String source;
    private static String handler;

    @BeforeAll
    static void readSource() throws IOException {
        assertTrue(Files.exists(SOURCE), "App kaynağı bulunamadı: " + SOURCE.toAbsolutePath());
        source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        handler = bodyOf("private static boolean initializeAppStateOrExit()");
    }

    @Test
    void appStateIsCreatedThroughTheGuardedPath() {
        String main = bodyOf("public static void main(String[] args)");
        assertTrue(main.contains("initializeAppStateOrExit()"),
                "AppState korumalı yol üzerinden kurulmalı");
        // Sıra: DB bekle → şema doğrula → AppState
        int db = main.indexOf("awaitDatabaseOrExit()");
        int schema = main.indexOf("verifySchemaOrExit()");
        int state = main.indexOf("initializeAppStateOrExit()");
        assertTrue(db >= 0 && schema > db && state > schema,
                "açılış sırası korunmalı: DB → şema → AppState");
    }

    @Test
    void layoutFailureIsHandledLikeTheOtherStartupGuards() {
        assertTrue(handler.contains("LayoutUnavailableException"),
                "masa düzeni hatası özel olarak ele alınmalı");
        assertTrue(handler.contains("LOG.error("), "teknik hata loglanmalı");
        assertTrue(handler.contains("JOptionPane.showMessageDialog"),
                "kullanıcıya mesaj gösterilmeli");
        assertTrue(handler.contains("GraphicsEnvironment.isHeadless()"),
                "headless modda diyalog açılmamalı");
        assertTrue(handler.contains("System.exit(2)"), "çıkış kodu 2 olmalı");
    }

    @Test
    void unrelatedFailuresAreNotSwallowed() {
        assertTrue(handler.contains("throw ex;"),
                "ilgisiz hata yeniden fırlatılmalı");
        assertTrue(handler.contains("ExceptionInInitializerError"),
                "tekil kurucu hatasının nedeni incelenmeli");
        assertFalse(handler.contains("catch (Throwable"),
                "Throwable yakalanmamalı");
        assertFalse(handler.contains("catch (Exception"),
                "genel Exception yakalanmamalı");
    }

    @Test
    void userMessageCarriesNoTechnicalDetail() {
        for (String forbidden : List.of("SQLState", "jdbc:", "getMessage()", "printStackTrace")) {
            assertFalse(handler.contains(forbidden),
                    "kullanıcıya teknik ayrıntı sızmamalı: " + forbidden);
        }
        assertTrue(handler.contains("logs/errors.log"),
                "kullanıcı teknik ayrıntının yerine yönlendirilmeli");
    }

    @Test
    void noFallbackLayoutIsReintroduced() {
        assertFalse(source.contains("restaurant-layout.properties"),
                "App properties düzenine dönmemeli");
        assertFalse(source.contains("AreaDefinition("),
                "App gömülü düzen kurmamalı");
    }

    // ------------------------------------------------------------------

    private static String bodyOf(String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("Metot bulunamadı: " + signature);
        }
        int open = source.indexOf('{', at + signature.length());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        fail("Gövde kapanışı bulunamadı: " + signature);
        return "";
    }
}
