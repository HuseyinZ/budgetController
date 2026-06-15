package service.expense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hızlı gider şablonlarını yöneten servis.
 *
 * <p><b>Kaynak öncelik sırası:</b>
 * <ol>
 *   <li><code>~/.budget/expense-templates.properties</code> (kullanıcı override'ı —
 *       restoran sahibi yeni şablon ekler/çıkarır)</li>
 *   <li>Classpath: <code>/expense-templates.properties</code> (JAR varsayılanı)</li>
 *   <li>Hardcoded fallback (dosya yoksa)</li>
 * </ol>
 *
 * <p><b>Properties format:</b>
 * <pre>
 * templates = Domates|🍅|kg, Biber|🌶️|kg, Kömür|⚫|kg, Kira|🏠|manuel
 * </pre>
 * Her şablon: <code>Ad|İkon|Mod</code>. Mod: <code>kg</code> veya <code>manuel</code>.
 *
 * <p><b>Düzenleme:</b> kullanıcı dosyayı <code>~/.budget/expense-templates.properties</code>
 * yoluna kopyalayıp yeni şablon ekleyebilir. Uygulamayı yeniden başlatması yeterli.
 */
public final class ExpenseTemplateService {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseTemplateService.class);

    private ExpenseTemplateService() {}

    /** Şablonları yükler (override → classpath → fallback). */
    public static List<ExpenseTemplate> loadTemplates() {
        // 1) Kullanıcı override
        Path userFile = Path.of(System.getProperty("user.home"),
                ".budget", "expense-templates.properties");
        if (Files.exists(userFile)) {
            try (InputStream in = Files.newInputStream(userFile)) {
                List<ExpenseTemplate> tpls = readFromStream(in);
                if (!tpls.isEmpty()) {
                    LOG.info("Gider şablonları yüklendi (override): {} adet", tpls.size());
                    return tpls;
                }
            } catch (IOException ex) {
                LOG.warn("Override şablon dosyası okunamadı: {}", ex.getMessage());
            }
        }
        // 2) Classpath default
        try (InputStream in = ExpenseTemplateService.class
                .getResourceAsStream("/expense-templates.properties")) {
            if (in != null) {
                List<ExpenseTemplate> tpls = readFromStream(in);
                if (!tpls.isEmpty()) return tpls;
            }
        } catch (IOException ex) {
            LOG.warn("Classpath şablon dosyası okunamadı: {}", ex.getMessage());
        }
        // 3) Fallback
        return defaultTemplates();
    }

    private static List<ExpenseTemplate> readFromStream(InputStream in) throws IOException {
        java.util.Properties p = new java.util.Properties();
        p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        String raw = p.getProperty("templates", "").trim();
        if (raw.isEmpty()) return List.of();
        List<ExpenseTemplate> result = new ArrayList<>();
        for (String chunk : raw.split(",")) {
            String s = chunk.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split("\\|");
            if (parts.length < 1) continue;
            String name = parts[0].trim();
            String icon = parts.length >= 2 ? parts[1].trim() : "📝";
            String mode = parts.length >= 3 ? parts[2].trim() : "manuel";
            if (name.isEmpty()) continue;
            result.add(new ExpenseTemplate(name, icon, mode));
        }
        return result;
    }

    /** Dosya yoksa kullanılacak varsayılan liste. */
    private static List<ExpenseTemplate> defaultTemplates() {
        return Arrays.asList(
                new ExpenseTemplate("Domates",   "🍅", "kg"),
                new ExpenseTemplate("Biber",     "🌶️", "kg"),
                new ExpenseTemplate("Soğan",     "🧅", "kg"),
                new ExpenseTemplate("Patates",   "🥔", "kg"),
                new ExpenseTemplate("Salatalık", "🥒", "kg"),
                new ExpenseTemplate("Marul",     "🥬", "kg"),
                new ExpenseTemplate("Limon",     "🍋", "kg"),
                new ExpenseTemplate("Maydanoz",  "🌿", "kg"),
                new ExpenseTemplate("Et (Dana)", "🥩", "kg"),
                new ExpenseTemplate("Et (Kuzu)", "🐑", "kg"),
                new ExpenseTemplate("Tavuk",     "🍗", "kg"),
                new ExpenseTemplate("Kıyma",     "🥩", "kg"),
                new ExpenseTemplate("Ciğer",     "🥩", "kg"),
                new ExpenseTemplate("Yumurta",   "🥚", "manuel"),
                new ExpenseTemplate("Süt",       "🥛", "manuel"),
                new ExpenseTemplate("Yoğurt",    "🥛", "kg"),
                new ExpenseTemplate("Peynir",    "🧀", "kg"),
                new ExpenseTemplate("Ekmek",     "🍞", "manuel"),
                new ExpenseTemplate("Lavaş",     "🥙", "manuel"),
                new ExpenseTemplate("Pirinç",    "🍚", "kg"),
                new ExpenseTemplate("Bulgur",    "🌾", "kg"),
                new ExpenseTemplate("Un",        "🌾", "kg"),
                new ExpenseTemplate("Yağ",       "🛢️", "manuel"),
                new ExpenseTemplate("Tuz",       "🧂", "kg"),
                new ExpenseTemplate("Baharat",   "🧂", "manuel"),
                new ExpenseTemplate("Kömür",     "⚫", "kg"),
                new ExpenseTemplate("Tüp gaz",   "🔥", "manuel"),
                new ExpenseTemplate("Elektrik",  "⚡", "manuel"),
                new ExpenseTemplate("Su",        "💧", "manuel"),
                new ExpenseTemplate("Doğalgaz",  "🔥", "manuel"),
                new ExpenseTemplate("Kira",      "🏠", "manuel"),
                new ExpenseTemplate("Maaş",      "💵", "manuel"),
                new ExpenseTemplate("Temizlik",  "🧴", "manuel"),
                new ExpenseTemplate("Servis",    "🛠️", "manuel"),
                new ExpenseTemplate("Vergi",     "📑", "manuel"),
                new ExpenseTemplate("Diğer",     "📝", "manuel")
        );
    }
}
