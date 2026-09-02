package service.db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Classpath'teki {@code db/migration/} dizininden migration'ları keşfeder.
 *
 * <p>YALNIZ bu dizin taranır (dosya sistemi checkout'u veya JAR içi).
 * {@code docs/db/legacy} classpath'te değildir ve ad deseni de eşleşmez;
 * hiçbir koşulda çalıştırılmaz. Alt dizinlere inilmez.
 */
public final class MigrationDiscovery {

    public static final String RESOURCE_DIR = "db/migration";

    private MigrationDiscovery() {}

    /** Uygulama classloader'ından keşif — versiyona göre artan sıra. */
    public static List<Migration> discover() {
        return discover(Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : MigrationDiscovery.class.getClassLoader());
    }

    public static List<Migration> discover(ClassLoader cl) {
        Map<String, String> files = new HashMap<>();
        try {
            Enumeration<URL> urls = cl.getResources(RESOURCE_DIR);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    readDirectory(Paths.get(url.toURI()), files);
                } else if ("jar".equals(url.getProtocol())) {
                    readJar(url, files);
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Migration dizini okunamadı: " + RESOURCE_DIR, e);
        }
        return fromFiles(files);
    }

    /** Ad→içerik haritasından sıralı, doğrulanmış migration listesi (test edilebilir çekirdek). */
    public static List<Migration> fromFiles(Map<String, String> files) {
        List<Migration> out = new ArrayList<>();
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            Migration m = Migration.fromFile(e.getKey(), e.getValue());
            if (m == null) continue; // desen dışı (README, .bak, legacy adları) → yok say
            String dup = seen.putIfAbsent(m.version(), e.getKey());
            if (dup != null) {
                throw new IllegalStateException("Aynı migration versiyonu iki dosyada: "
                        + dup + " ve " + e.getKey());
            }
            out.add(m);
        }
        out.sort(Comparator.comparingInt(Migration::version));
        return Collections.unmodifiableList(out);
    }

    private static void readDirectory(Path dir, Map<String, String> files) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    files.put(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static void readJar(URL url, Map<String, String> files) throws IOException {
        JarURLConnection conn = (JarURLConnection) url.openConnection();
        // KRİTİK: cache'li JarFile classloader ile paylaşılır; onu kapatmak uygulamanın
        // kendi JAR'ını kapatır ("zip file closed"). Kendi örneğimizi açıp kapatıyoruz.
        conn.setUseCaches(false);
        String prefix = RESOURCE_DIR + "/";
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.contains("/")) continue; // alt dizin yok say
                try (InputStream in = jar.getInputStream(entry)) {
                    files.put(rel, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
    }
}
