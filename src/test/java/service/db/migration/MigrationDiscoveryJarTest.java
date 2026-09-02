package service.db.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gerçek {@code jar:} URL senaryosu — paketlenmiş uygulamada (shade JAR) keşif.
 * Geçici bir JAR üretilir ve ebeveynsiz URLClassLoader ile taranır; böylece
 * test-classpath'teki dizin kaynakları devreye girmez.
 */
class MigrationDiscoveryJarTest {

    @TempDir
    Path tmp;

    @Test
    void discoversFromJarInOrderAndIgnoresNestedAndLegacy() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        // Sıralama testi için bilinçli karışık ekleme sırası
        entries.put("db/migration/V003__seed_kitchen_printers.sql", "INSERT INTO kitchen_printers (code) VALUES ('X');");
        entries.put("db/migration/V001__baseline_schema.sql", "CREATE TABLE t (id INT);");
        entries.put("db/migration/V002__seed_roles.sql", "INSERT INTO roles (name) VALUES ('ADMIN');");
        // Keşfedilmemesi gerekenler
        entries.put("db/migration/README.md", "docs");
        entries.put("db/migration/V001__baseline_schema.sql.bak", "eski");
        entries.put("db/migration/legacy/V004__nested_should_be_ignored.sql", "SELECT 4;");
        entries.put("db/migration/V2026_05_15__kitchen_printers.sql", "legacy ad deseni");
        entries.put("docs/db/legacy/V005__outside_dir.sql", "SELECT 5;");

        Path jar = tmp.resolve("app.jar");
        writeJar(jar, entries);

        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            List<Migration> found = MigrationDiscovery.discover(cl);
            assertEquals(List.of(1, 2, 3), found.stream().map(Migration::version).toList(),
                    "JAR içinden yalnız V001-V003, numerik sırada");
            assertEquals("baseline schema", found.get(0).description());
            assertTrue(found.stream().noneMatch(m -> m.version() >= 4), "nested/dış dizin dosyaları keşfedilmemeli");
            assertEquals(Migration.sha256Hex("CREATE TABLE t (id INT);"), found.get(0).checksum(),
                    "içerik JAR'dan birebir okunmalı");
        }
    }

    @Test
    void emptyJarYieldsNoMigrations() throws Exception {
        Path jar = tmp.resolve("empty.jar");
        writeJar(jar, Map.of("META-INF/x.txt", "x"));
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            assertTrue(MigrationDiscovery.discover(cl).isEmpty());
        }
    }

    /** Dizin girdileri de yazılır — URLClassLoader.getResources("db/migration") bunlara ihtiyaç duyar. */
    private static void writeJar(Path jar, Map<String, String> entries) throws Exception {
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            java.util.Set<String> dirs = new java.util.LinkedHashSet<>();
            for (String name : entries.keySet()) {
                String[] parts = name.split("/");
                StringBuilder path = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    path.append(parts[i]).append('/');
                    dirs.add(path.toString());
                }
            }
            for (String d : dirs) {
                out.putNextEntry(new JarEntry(d));
                out.closeEntry();
            }
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }
}
