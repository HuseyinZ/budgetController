package state;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Açılış ve poller yollarının SALT OKUMA kaldığını doğrulayan koruma testi.
 *
 * <p><b>Neden kaynak seviyesinde?</b> {@code AppState} bir singleton'dır ve
 * kurucusunda kendi servislerini üretir; collaborator enjekte edilemediği için
 * gerçek bir örnek oluşturmadan davranışı gözlemek mümkün değil, örnek
 * oluşturmak ise çalışan bir veritabanı ister. {@code AppState} dekompozisyonu
 * ayrı ve ertelenmiş bir iştir. Bu test, korunması istenen değişmezi —
 * "açılış ve poller masa YAZMAZ" — kod düzeyinde kilitler: birisi salt okuma
 * yoluna mutasyon çağrısı geri koyarsa test kırılır.
 */
class AppStateStartupReadOnlyTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "state", "AppState.java");

    /** Masa oluşturan tek yol — salt okuma metotlarında görünmemeli. */
    private static final String MUTATING_RESOLVER = "ensureTableExists(";
    private static final String CREATE_TABLE = "createTable(";

    private static String source;

    @BeforeAll
    static void readSource() throws IOException {
        assertTrue(Files.exists(SOURCE), "AppState kaynağı bulunamadı: " + SOURCE.toAbsolutePath());
        source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void constructorUsesTheReadOnlyCacheInitializer() {
        String ctor = bodyOf("private AppState()");
        assertTrue(ctor.contains("initializeTableCache()"),
                "kurucu salt okuma önbelleğini kurmalı");
        assertFalse(ctor.contains("initializeTables()"),
                "eski yazan açılış metodu çağrılmamalı");
        assertNoWrite(ctor, "AppState kurucusu");
    }

    @Test
    void startupCacheOnlyReadsExistingTables() {
        String body = bodyOf("private void initializeTableCache()");
        assertNoWrite(body, "initializeTableCache");
        assertTrue(body.contains("getAllTables()"),
                "açılış mevcut kayıtları tek SELECT ile okumalı");
    }

    @Test
    void startupCacheFailsFastInsteadOfSwallowingErrors() {
        // StartupHealth ve SchemaStartupCheck geçtikten sonra çalıştığı için,
        // buradaki bir okuma hatası gizlenmemeli; RuntimeException yukarı çıkmalı.
        String body = bodyOf("private void initializeTableCache()");
        assertFalse(body.contains("catch"),
                "açılış önbelleği hatayı yutmamalı, fail-fast olmalı");
    }

    @Test
    void pollerPathNeverCreatesTables() {
        assertNoWrite(bodyOf("private void pollTables()"), "pollTables");

        String capture = bodyOf("private TableSignature captureSignature(int tableNo)");
        assertNoWrite(capture, "captureSignature");
        assertTrue(capture.contains("findExistingTableId("),
                "poller salt okuma çözümleyicisini kullanmalı");
        assertTrue(capture.contains("TableStatus.EMPTY"),
                "eksik masa EMPTY olarak raporlanmalı");
    }

    @Test
    void snapshotPathNeverCreatesTables() {
        String body = bodyOf("public synchronized TableSnapshot snapshot(int tableNo)");
        assertNoWrite(body, "snapshot");
        assertTrue(body.contains("findExistingTableId("),
                "snapshot salt okuma çözümleyicisini kullanmalı");
    }

    @Test
    void transferTargetsIncludeTablesWithoutDatabaseRows() {
        String body = bodyOf("public synchronized List<Integer> getAvailableTransferTargets(int fromTableNo, User user)");
        assertNoWrite(body, "getAvailableTransferTargets");
        assertTrue(body.contains("layouts.keySet()"),
                "tüm yapılandırılmış masalar değerlendirilmeli");
        assertTrue(body.contains("findExistingTableId("),
                "salt okuma çözümleyicisi kullanılmalı");
        assertFalse(body.contains("tableIds.get("),
                "yalnız yerel cache'e bakmak DB kaydı olmayan boş masayı elerdi");
        assertTrue(body.contains("tableId != null && orderService.getOpenOrderByTable(tableId).isPresent()"),
                "açık sipariş kontrolü yalnız kaydı olan masaya uygulanmalı");
        assertTrue(body.contains("canAccessTable("), "erişim kontrolü korunmalı");
    }

    @Test
    void kitchenSendResolvesTablesCreatedByOtherInstances() {
        // Masa, bu örneğin açılışından sonra başka bir PC'de oluşmuş olabilir;
        // yalnız yerel cache'e bakmak mutfak fişini sessizce düşürürdü.
        String body = bodyOf("public List<PrintingService.PrintResult> sendOrderToKitchens(int tableNo,");
        assertNoWrite(body, "sendOrderToKitchens");
        assertTrue(body.contains("findExistingTableId("),
                "masa salt okuma ile yeniden çözümlenmeli");
        assertFalse(body.contains("tableIds.get("), "yalnız yerel cache'e güvenilmemeli");
    }

    @Test
    void readOnlyResolverDoesNotWrite() {
        String body = bodyOf("private Long findExistingTableId(int tableNo)");
        assertFalse(body.contains(CREATE_TABLE), "findExistingTableId masa oluşturmamalı");
        assertFalse(body.contains(MUTATING_RESOLVER), "findExistingTableId mutasyon yoluna sapmamalı");
        assertTrue(body.contains("getByTableNo("), "yalnız okuma yapmalı");
    }

    @Test
    void mutatingResolverStillExistsForUserActions() {
        // Kullanıcı aksiyonları (sipariş, ödeme, taşıma) bu aşamada davranışını korur.
        String body = bodyOf("private Long ensureTableExists(int tableNo)");
        assertTrue(body.contains(CREATE_TABLE),
                "gerçek kullanıcı aksiyonlarının masa oluşturma yolu korunmalı");
    }

    // ------------------------------------------------------------------

    private static void assertNoWrite(String body, String where) {
        assertFalse(body.contains(MUTATING_RESOLVER),
                where + " içinde masa oluşturan çözümleyici çağrılmamalı");
        assertFalse(body.contains(CREATE_TABLE),
                where + " içinde createTable çağrılmamalı");
    }

    /** İmzadan başlayıp süslü parantezleri eşleyerek metot gövdesini çıkarır. */
    private static String bodyOf(String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("Metot bulunamadı: " + signature);
        }
        int open = source.indexOf('{', at + signature.length());
        if (open < 0) {
            fail("Gövde açılışı bulunamadı: " + signature);
        }
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
