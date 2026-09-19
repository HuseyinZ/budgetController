package service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code restaurant-layout.properties} kullanımdan kaldırıldı: ne kaynak
 * olarak paketlenir, ne runtime'da okunur, ne de dokümanlarda aktifmiş gibi
 * anlatılır.
 */
class LayoutConfigurationRetiredTest {

    private static final String RESOURCE = "restaurant-layout.properties";

    @Test
    void theResourceIsNoLongerPackaged() {
        assertFalse(Files.exists(Path.of("src", "main", "resources", RESOURCE)),
                "kaynak dosya repodan kaldırılmalı (git rm)");
        assertNull(LayoutConfigurationRetiredTest.class.getResourceAsStream("/" + RESOURCE),
                "classpath'te layout properties bulunmamalı");
    }

    @Test
    void noProductionSourceReadsIt() throws IOException {
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = Files.readString(p, StandardCharsets.UTF_8);
                // Javadoc'ta "artık okunmuyor" demek serbest; yasak olan GERÇEK okuma.
                if (body.contains("getResourceAsStream(\"/" + RESOURCE)
                        || body.contains("\".budget\", \"" + RESOURCE + "\"")) {
                    offenders.add(p);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "layout properties hâlâ okunuyor: " + offenders);
    }

    @Test
    void documentationDoesNotPresentItAsActive() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("docs"))) {
            for (Path p : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n")) {
                    if (!line.contains(RESOURCE)) {
                        continue;
                    }
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean retired = lower.contains("kaldırıl") || lower.contains("artık")
                            || lower.contains("eski") || lower.contains("yok");
                    if (!retired) {
                        offenders.add(p.getFileName() + ": " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "doküman layout properties'i aktifmiş gibi anlatıyor: " + offenders);
    }
}
