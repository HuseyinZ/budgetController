package service.db.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration dosyasını çalıştırılabilir ifadelere böler.
 *
 * <p>Kurallar (V001 başlığındaki sözleşmeyle aynı):
 * <ul>
 *   <li>Tam satır {@code --} yorumları atılır; satır içi {@code --} yorumu
 *       yalnız tırnak dışındaysa kesilir.</li>
 *   <li>İfadeler tırnak DIŞINDAKİ {@code ;} ile ayrılır; tek tırnak içindeki
 *       {@code ;} ayraç sayılmaz (yine de dosya sözleşmesi literal içinde
 *       noktalı virgül kullanmamayı şart koşar).</li>
 *   <li>{@code DELIMITER} / routine desteklenmez.</li>
 * </ul>
 */
public final class SqlScript {

    private SqlScript() {}

    public static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        String s = script == null ? "" : script;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (inQuote) {
                cur.append(c);
                if (c == '\'') {
                    // '' kaçışı
                    if (i + 1 < n && s.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inQuote = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inQuote = true;
                cur.append(c);
                continue;
            }
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                // yorum: satır sonuna kadar atla
                while (i < n && s.charAt(i) != '\n') i++;
                cur.append('\n');
                continue;
            }
            if (c == ';') {
                addIfNotBlank(out, cur);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        addIfNotBlank(out, cur);
        return out;
    }

    private static void addIfNotBlank(List<String> out, StringBuilder cur) {
        String stmt = cur.toString().trim();
        if (!stmt.isEmpty()) out.add(stmt);
    }
}
