package tools;

import service.print.Receipt;
import service.print.TcpEscPosPrinter;
import service.print.PrinterException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Yazıcı donanımı test aracı.
 *
 * <p>Yazıcının IP'sini öğrendikten sonra (yazıcının kendi ekranı /
 * web panelinden veya router DHCP listesinden) bu CLI ile küçük
 * bir Türkçe test fişi basabilirsiniz. Hiçbir DB veya UI gerekmez —
 * sadece socket çalışır.
 *
 * <p>Kullanım (proje root'unda):
 * <pre>{@code
 *   mvn -q -DskipTests package
 *   java -cp target/budgetController-1.0-SNAPSHOT.jar \
 *        tools.PrintTestMain 192.168.1.241 9100
 * }</pre>
 *
 * <p>İlk arg yazıcı IP'si, ikinci arg (opsiyonel) port (default 9100),
 * üçüncü arg (opsiyonel) satır başına karakter (default 42, 58mm yazıcı için 32).
 */
public final class PrintTestMain {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Kullanım: java tools.PrintTestMain <ip> [port] [charsPerLine]");
            System.err.println("  örn:   java tools.PrintTestMain 192.168.1.241 9100 42");
            System.exit(1);
        }

        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9100;
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 42;

        System.out.println("Yazıcıya bağlanılıyor: " + host + ":" + port + "  (genişlik=" + width + ")");

        Receipt sample = new Receipt(
                "*** OCAK ***",
                "SALON A",
                "5",
                "Ahmet Garson",
                LocalDateTime.now(),
                List.of(
                        new Receipt.Line(2, "Kuzu Ciğer Şiş", "Az pişmiş", true),
                        new Receipt.Line(1, "Adana Kebap", null, true),
                        new Receipt.Line(3, "Ayran", null, false),       // başka mutfak
                        new Receipt.Line(1, "Şakşuka", "Acılı", false)   // başka mutfak
                ),
                "Acele lütfen — masa bekliyor",
                12345L
        );

        TcpEscPosPrinter printer = new TcpEscPosPrinter("TEST", "Test Yazıcı",
                host, port, width, 5000, 5000);
        try {
            printer.print(sample);
            System.out.println("OK — fiş basıldı.");
        } catch (PrinterException e) {
            System.err.println("HATA: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("       " + e.getCause().getClass().getSimpleName()
                        + ": " + e.getCause().getMessage());
            }
            System.exit(2);
        }
    }
}
