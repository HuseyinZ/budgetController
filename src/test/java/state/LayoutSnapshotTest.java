package state;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Masa düzeni anlık görüntüsü değişmezdir ve tek atomik takasla değiştirilir.
 *
 * <p>Gerçek {@code AppState} örneği oluşturmak çalışan bir veritabanı ister;
 * bu yüzden değişmezlik doğrudan, takas sırası ise kaynak düzeyinde doğrulanır.
 */
class LayoutSnapshotTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "state", "AppState.java");
    private static String source;

    @BeforeAll
    static void readSource() throws IOException {
        source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static AppState.LayoutSnapshot snapshot() {
        Map<Integer, AppState.TableLayout> layouts = new LinkedHashMap<>();
        layouts.put(7, new AppState.TableLayout(7, "A", "Kat"));
        layouts.put(19, new AppState.TableLayout(19, "A", "Kat"));
        layouts.put(4020, new AppState.TableLayout(4020, "A", "Kat"));
        return new AppState.LayoutSnapshot(
                List.of(new AppState.AreaDefinition("A", "Kat", "", List.of(7, 19, 4020))),
                layouts);
    }

    @Test
    void snapshotKeepsNonContiguousTableNumbersInOrder() {
        AppState.LayoutSnapshot s = snapshot();
        assertEquals(List.of(7, 19, 4020), List.copyOf(s.tableNumbers()));
        assertEquals(3, s.tableCount());
        assertTrue(s.contains(4020));
        assertFalse(s.contains(8));
    }

    @Test
    void snapshotIsImmutable() {
        AppState.LayoutSnapshot s = snapshot();
        assertThrows(UnsupportedOperationException.class, () -> s.areas().add(null));
        assertThrows(UnsupportedOperationException.class, () -> s.tableNumbers().remove(7));
        assertThrows(UnsupportedOperationException.class,
                () -> s.areas().get(0).getTableNumbers().add(99));
    }

    @Test
    void mutatingTheSourceMapDoesNotAffectAnExistingSnapshot() {
        Map<Integer, AppState.TableLayout> layouts = new LinkedHashMap<>();
        layouts.put(101, new AppState.TableLayout(101, "A", "Kat"));
        AppState.LayoutSnapshot s = new AppState.LayoutSnapshot(
                List.of(new AppState.AreaDefinition("A", "Kat", "", List.of(101))), layouts);

        layouts.put(102, new AppState.TableLayout(102, "A", "Kat"));

        assertEquals(1, s.tableCount(), "görüntü kendi kopyasını tutmalı");
        assertFalse(s.contains(102));
    }

    @Test
    void reloadValidatesBeforeSwappingAtomically() {
        String body = bodyOf("public void reloadLayout()");
        int build = body.indexOf("loadSnapshotFromDatabase()");
        int swap = body.indexOf("layoutRef.set(");
        assertTrue(build >= 0 && swap > build,
                "yeni düzen takastan ÖNCE tamamen kurulmalı");
        assertEquals(1, body.split("layoutRef\\.set\\(", -1).length - 1,
                "tek atomik takas olmalı");
        assertFalse(body.contains("layoutRef.get()"),
                "takas sırasında eski görüntü okunup karıştırılmamalı");
    }

    @Test
    void failedReloadKeepsThePreviousSnapshot() {
        // loadSnapshotFromDatabase() istisna fırlatırsa set() satırına hiç
        // ulaşılmaz; eski referans olduğu gibi kalır.
        String body = bodyOf("public void reloadLayout()");
        int build = body.indexOf("LayoutSnapshot fresh = loadSnapshotFromDatabase();");
        assertTrue(build >= 0, "yükleme ayrı bir adım olmalı: " + body);
        assertFalse(body.contains("catch"),
                "hata yutulmamalı — yükleme başarısızsa eski düzen korunur ve istisna yüzeye çıkar");
    }

    @Test
    void readersAlwaysGoThroughTheSnapshot() {
        // Yasak olan, AppState'in KENDİ alanında ayrı bir düzen state'i tutması.
        // LayoutSnapshot'ın kendi (değişmez) alanları meşrudur, bu yüzden yalnız
        // AppState seviyesindeki alan bildirimleri (4 boşluk girinti) taranır;
        // iç sınıf alanları 8 boşlukla başlar ve kapsam dışı kalır.
        List<String> offenders = new java.util.ArrayList<>();
        for (String raw : source.split("\n")) {
            String line = raw.stripTrailing();
            if (!line.startsWith("    private ") || !line.endsWith(";")) {
                continue;
            }
            if (line.contains("AtomicReference<LayoutSnapshot>")) {
                continue;
            }
            if (line.contains("Map<Integer, TableLayout>") || line.contains("List<AreaDefinition>")) {
                offenders.add(line.trim());
            }
        }
        assertTrue(offenders.isEmpty(),
                "AppState ayrı düzen state'i tutmamalı: " + offenders);
        assertTrue(source.contains("AtomicReference<LayoutSnapshot>"),
                "tek atomik referans kullanılmalı");
    }

    @Test
    void staleCachesArePrunedWithoutDatabaseWrites() {
        String body = bodyOf("private void pruneStaleTableCaches(LayoutSnapshot snapshot)");
        assertTrue(body.contains("tableIds.keySet().removeIf"), body);
        assertTrue(body.contains("tableSignatures.keySet().removeIf"), body);
        for (String write : List.of("createTable(", "ensureTableExists(", "INSERT", "DELETE FROM")) {
            assertFalse(body.contains(write), "önbellek temizliği DB'ye yazmamalı: " + write);
        }
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
