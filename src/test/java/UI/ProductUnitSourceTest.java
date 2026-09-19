package UI;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Birim listesinin UI'da hardcoded kalmadığını ve iki ekranın AYNI DB
 * kaynağını kullandığını doğrular.
 *
 * <p>Swing bileşenleri headless CI'da örneklenemediği için kontrol kaynak
 * düzeyinde yapılır.
 */
class ProductUnitSourceTest {

    private static final Path UI_DIR = Path.of("src", "main", "java", "UI");

    private static String read(String file) throws IOException {
        Path p = UI_DIR.resolve(file);
        assertTrue(Files.exists(p), "kaynak bulunamadı: " + p.toAbsolutePath());
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void neitherScreenDeclaresAHardcodedUnitList() throws IOException {
        for (String file : List.of("ProductsPanel.java", "ProductEditDialog.java")) {
            String src = read(file);
            assertFalse(src.contains("new JComboBox<>(new String[]{"),
                    file + " birim listesini koda gömmemeli");
            // Eski sabit liste imzası
            assertFalse(src.contains("\"porsiyon\", \"şiş\", \"adet\", \"kg\", \"tabak\", \"kase\""),
                    file + " hardcoded birim dizisi içermemeli");
        }
    }

    @Test
    void bothScreensUseTheSameDatabaseBackedSource() throws IOException {
        for (String file : List.of("ProductsPanel.java", "ProductEditDialog.java")) {
            String src = read(file);
            assertTrue(src.contains("ProductUnitOptions.load("),
                    file + " birimleri ortak DB kaynağından yüklemeli");
            assertTrue(src.contains("ProductUnitOptions.select("),
                    file + " seçimi ortak yardımcı üzerinden yapmalı");
            assertFalse(src.contains("unitLabelCombo.setEditable(true)"),
                    file + " serbest birim girişine izin vermemeli");
        }
    }

    @Test
    void helperReadsOnlyActiveUnitsFromAppState() throws IOException {
        String src = read("ProductUnitOptions.java");
        assertTrue(src.contains("AppState.getInstance().getActiveProductUnits()"),
                "birimler AppState üzerinden DB'den gelmeli");
        assertTrue(src.contains("setEditable(false)"), "combo salt seçim olmalı");
        assertTrue(src.contains("DEFAULT_CODE = \"porsiyon\""), "varsayılan porsiyon olmalı");
    }

    @Test
    void helperHasNoHardcodedUnitFallback() throws IOException {
        String src = read("ProductUnitOptions.java");
        for (String code : List.of("\"şiş\"", "\"adet\"", "\"kg\"", "\"tabak\"", "\"kase\"")) {
            assertFalse(src.contains(code),
                    "DB okunamazsa gömülü listeye düşülmemeli, bulunan: " + code);
        }
        assertTrue(src.contains("JOptionPane.showMessageDialog"),
                "okuma hatası kullanıcıya görünür olmalı");
    }

    @Test
    void appStateExposesTheDatabaseBackedReader() throws IOException {
        String src = Files.readString(
                Path.of("src", "main", "java", "state", "AppState.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("getActiveProductUnits()"), "AppState okuma metodu sunmalı");
        assertEquals(1, src.split("productUnitService\\.getActiveUnits\\(\\)", -1).length - 1,
                "tek okuma noktası beklenir");
    }
}
