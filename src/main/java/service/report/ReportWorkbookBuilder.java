package service.report;

import DataConnection.Db;
import model.MoneyUtil;
import model.Payment;
import model.PaymentMethod;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.PaymentService;
import state.AppState;
import state.ExpenseRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static model.MoneyUtil.formatTl;

/**
 * UI'dan bağımsız Excel raporu üreteci.
 *
 * <p>Hem {@link UI.DailyReportPanel} hem de otomatik gönderim
 * scheduler'ı tarafından kullanılır.
 *
 * <p>Kapsam: Gün Sonu / Ay Sonu raporu — Toplam ciro, gider, net kar,
 * sipariş sayısı, ödeme yöntemi dağılımı, saatlik dağılım, ürün satış özeti,
 * gider listesi.
 */
public final class ReportWorkbookBuilder {

    public static final String XLSX_MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Logger LOG = LoggerFactory.getLogger(ReportWorkbookBuilder.class);

    private ReportWorkbookBuilder() {}

    // ============================================================
    //   Genel kullanım
    // ============================================================

    public static byte[] buildDailyReport(AppState appState, LocalDate date) throws IOException {
        ReportData data = collectDaily(appState, date);
        return write(data);
    }

    public static byte[] buildMonthlyReport(AppState appState, YearMonth ym) throws IOException {
        ReportData data = collectMonthly(appState, ym);
        return write(data);
    }

    public static String dailyFileName(LocalDate date) { return "gun-sonu-" + date + ".xlsx"; }
    public static String monthlyFileName(YearMonth ym) {
        return String.format("ay-sonu-%04d-%02d.xlsx", ym.getYear(), ym.getMonthValue());
    }

    // ============================================================
    //   Veri toplama
    // ============================================================

    public static ReportData collectDaily(AppState appState, LocalDate date) {
        PaymentService paymentService = new PaymentService();
        List<Payment> payments = paymentService.getPaymentsOn(date);
        BigDecimal totalExpense = appState.getExpenseTotal(date);

        List<ProductSummaryRow> products = loadProductSummary(date.atStartOfDay(),
                date.plusDays(1).atStartOfDay());
        List<ExpenseRecord> expenses = appState.getExpensesOn(date);

        return createReportData(false, date.toString(), payments, totalExpense, products, expenses);
    }

    public static ReportData collectMonthly(AppState appState, YearMonth ym) {
        PaymentService paymentService = new PaymentService();
        List<Payment> payments = paymentService.getPaymentsInMonth(ym.getYear(), ym.getMonthValue());
        BigDecimal totalExpense = appState.getExpenseTotal(ym);

        LocalDate first = ym.atDay(1);
        LocalDate firstOfNext = first.plusMonths(1);
        List<ProductSummaryRow> products = loadProductSummary(first.atStartOfDay(),
                firstOfNext.atStartOfDay());

        List<ExpenseRecord> expenses = new ArrayList<>();
        for (LocalDate d = first; d.isBefore(firstOfNext); d = d.plusDays(1)) {
            expenses.addAll(appState.getExpensesOn(d));
        }

        return createReportData(true,
                String.format("%04d-%02d", ym.getYear(), ym.getMonthValue()),
                payments, totalExpense, products, expenses);
    }

    // ============================================================
    //   Workbook yazımı
    // ============================================================

    public static byte[] write(ReportData data) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(data.monthly() ? "Ay Sonu" : "Gün Sonu");

            CellStyle bold = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold.setFont(boldFont);

            int r = 0;
            Row title = sheet.createRow(r++);
            Cell tc = title.createCell(0);
            tc.setCellValue((data.monthly() ? "AY SONU RAPORU - " : "GÜN SONU RAPORU - ")
                    + data.periodLabel());
            tc.setCellStyle(bold);

            r++;
            sheet.createRow(r++).createCell(0).setCellValue("Toplam Ciro: " + formatTl(data.totalSales()));
            sheet.createRow(r++).createCell(0).setCellValue("Toplam Gider: " + formatTl(data.totalExpense()));
            sheet.createRow(r++).createCell(0).setCellValue("Net Kar: " + formatTl(data.netProfit()));
            sheet.createRow(r++).createCell(0).setCellValue("Sipariş Sayısı: " + data.payments().size());

            // Ödeme yöntemi
            r++;
            createBoldSectionHeader(sheet, r++, "ÖDEME YÖNTEMİ DAĞILIMI", bold);
            EnumMap<PaymentMethod, int[]> countByMethod = new EnumMap<>(PaymentMethod.class);
            EnumMap<PaymentMethod, BigDecimal> sumByMethod = new EnumMap<>(PaymentMethod.class);
            for (Payment p : data.payments()) {
                PaymentMethod m = p.getMethod() == null ? PaymentMethod.CASH : p.getMethod();
                countByMethod.computeIfAbsent(m, k -> new int[]{0})[0]++;
                sumByMethod.merge(m, p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(), BigDecimal::add);
            }
            for (PaymentMethod m : PaymentMethod.values()) {
                int[] c = countByMethod.get(m);
                BigDecimal s = sumByMethod.getOrDefault(m, BigDecimal.ZERO);
                if (c == null && s.signum() == 0) continue;
                writeDistributionRow(sheet, r++, describeMethod(m), c == null ? 0 : c[0], s);
            }

            // Saatlik dağılım
            r++;
            createBoldSectionHeader(sheet, r++, "SAATLİK İŞLEM DAĞILIMI", bold);
            int[] perHour = new int[24];
            BigDecimal[] sumPerHour = new BigDecimal[24];
            for (int i = 0; i < 24; i++) sumPerHour[i] = BigDecimal.ZERO;
            for (Payment p : data.payments()) {
                LocalDateTime at = p.getPaidAt();
                if (at == null) continue;
                int h = at.getHour();
                perHour[h]++;
                sumPerHour[h] = sumPerHour[h].add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount());
            }
            for (int h = 0; h < 24; h++) {
                if (perHour[h] == 0 && sumPerHour[h].signum() == 0) continue;
                writeDistributionRow(sheet, r++, String.format("%02d:00", h),
                        perHour[h], sumPerHour[h]);
            }

            // Ürün satış özeti
            r++;
            createBoldSectionHeader(sheet, r++, "ÜRÜN SATIŞ ÖZETİ", bold);
            Row psHdr = sheet.createRow(r++);
            psHdr.createCell(0).setCellValue("Ürün");
            psHdr.createCell(1).setCellValue("Birim");
            psHdr.createCell(2).setCellValue("Satılan");
            psHdr.createCell(3).setCellValue("Porsiyon Karşılığı");
            for (ProductSummaryRow ps : data.products()) {
                String unit = (ps.unitLabel() == null || ps.unitLabel().isBlank())
                        ? (ps.piecesPerPortion() > 0 ? "şiş" : "porsiyon")
                        : ps.unitLabel();
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(ps.productName());
                row.createCell(1).setCellValue(unit);
                row.createCell(2).setCellValue(ps.totalQty() + " " + unit);
                if (ps.piecesPerPortion() > 0) {
                    double portions = ps.totalQty() / (double) ps.piecesPerPortion();
                    row.createCell(3).setCellValue(String.format(MoneyUtil.TURKISH_LOCALE,
                            "%.2f porsiyon  (1 porsiyon = %d %s)",
                            portions, ps.piecesPerPortion(), unit));
                } else {
                    row.createCell(3).setCellValue(ps.totalQty() + " porsiyon");
                }
            }

            // Giderler
            r++;
            createBoldSectionHeader(sheet, r++, "GİDERLER", bold);
            Row hdr = sheet.createRow(r++);
            hdr.createCell(0).setCellValue("Tarih");
            hdr.createCell(1).setCellValue("Açıklama");
            hdr.createCell(2).setCellValue("Tutar");
            for (ExpenseRecord e : data.expenses()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(e.getExpenseDate() == null ? "" : e.getExpenseDate().toString());
                row.createCell(1).setCellValue(e.getDescription() == null ? "" : e.getDescription());
                row.createCell(2).setCellValue(e.getAmount() == null ? 0.0 : e.getAmount().doubleValue());
            }

            for (int i = 0; i < 4; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ============================================================
    //   Yardımcılar
    // ============================================================

    private static void writeDistributionRow(Sheet sheet, int rowIndex,
                                             String label, int count, BigDecimal amount) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(label);
        row.createCell(1).setCellValue(count);
        row.createCell(2).setCellValue(formatTl(amount));
    }

    private static void createBoldSectionHeader(Sheet sheet, int rowIndex,
                                                String title, CellStyle bold) {
        Row sectionHeader = sheet.createRow(rowIndex);
        Cell cell = sectionHeader.createCell(0);
        cell.setCellValue(title);
        cell.setCellStyle(bold);
    }

    private static BigDecimal sumPayments(List<Payment> payments) {
        return MoneyUtil.sumAmounts(payments, Payment::getAmount)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static ReportData createReportData(boolean monthly,
                                               String periodLabel,
                                               List<Payment> payments,
                                               BigDecimal totalExpense,
                                               List<ProductSummaryRow> products,
                                               List<ExpenseRecord> expenses) {
        BigDecimal totalSales = sumPayments(payments);
        BigDecimal netProfit = totalSales.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP);
        return new ReportData(monthly, periodLabel, payments, totalSales,
                totalExpense, netProfit, products, expenses);
    }

    private static String describeMethod(PaymentMethod m) {
        return m.getDisplayName();
    }

    /**
     * Ürün satış özeti — verilen aralık (start dahil, end hariç)
     * için order_items + payments join'i.
     * Snapshot kolonları yoksa fallback ile minimum sorgu.
     */
    private static List<ProductSummaryRow> loadProductSummary(LocalDateTime startInclusive,
                                                              LocalDateTime endExclusive) {
        List<ProductSummaryRow> rows = new ArrayList<>();
        final String sql =
                "SELECT oi.product_name, " +
                "       COALESCE(oi.unit_label, '') AS unit_label, " +
                "       COALESCE(MAX(oi.pieces_per_portion), 0) AS pp, " +
                "       SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments p ON p.order_id = oi.order_id " +
                " WHERE p.paid_at >= ? AND p.paid_at < ? " +
                " GROUP BY oi.product_name, COALESCE(oi.unit_label, '') " +
                " ORDER BY total_qty DESC";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(startInclusive));
            ps.setTimestamp(2, Timestamp.valueOf(endExclusive));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ProductSummaryRow(
                            rs.getString("product_name"),
                            rs.getString("unit_label"),
                            rs.getInt("total_qty"),
                            rs.getInt("pp")));
                }
            }
        } catch (SQLException ex) {
            // Eski şema → fallback
            try (Connection c = Db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT oi.product_name, SUM(oi.quantity) AS total_qty " +
                     "  FROM order_items oi " +
                     "  JOIN payments p ON p.order_id = oi.order_id " +
                     " WHERE p.paid_at >= ? AND p.paid_at < ? " +
                     " GROUP BY oi.product_name " +
                     " ORDER BY total_qty DESC")) {
                ps.setTimestamp(1, Timestamp.valueOf(startInclusive));
                ps.setTimestamp(2, Timestamp.valueOf(endExclusive));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ProductSummaryRow(
                                rs.getString("product_name"),
                                "", rs.getInt("total_qty"), 0));
                    }
                }
            } catch (SQLException ignore) {
                LOG.warn("Ürün satış özeti alınamadı: {}", ex.getMessage());
            }
        }
        return rows;
    }

    // ============================================================
    //   Veri sınıfları
    // ============================================================

    public record ReportData(
            boolean monthly,
            String periodLabel,
            List<Payment> payments,
            BigDecimal totalSales,
            BigDecimal totalExpense,
            BigDecimal netProfit,
            List<ProductSummaryRow> products,
            List<ExpenseRecord> expenses
    ) {}

    public record ProductSummaryRow(
            String productName,
            String unitLabel,
            int totalQty,
            int piecesPerPortion
    ) {}
}
