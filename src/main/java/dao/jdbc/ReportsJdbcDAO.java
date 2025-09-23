package dao.jdbc;

import DataConnection.Db;
import dao.ReportsDAO;
import model.PaymentMethod;
import model.ProductSalesRow;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReportsJdbcDAO implements ReportsDAO {

    private final DataSource dataSource;

    public ReportsJdbcDAO() {
        this(Db.getDataSource());
    }

    public ReportsJdbcDAO(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<ProductSalesRow> findProductSalesBefore(LocalDateTime threshold) {
        String sql = "SELECT p.paid_at AS sold_at, " +
                "oi.product_name, " +
                "c.name AS category_name, " +
                "oi.quantity, " +
                "p.method AS payment_method, " +
                "COALESCE(oi.line_total, oi.quantity * oi.unit_price * 1.20) AS amount_total " +
                "FROM payments p " +
                "JOIN orders o ON o.id = p.order_id " +
                "JOIN order_items oi ON oi.order_id = o.id " +
                "LEFT JOIN products pr ON pr.id = oi.product_id " +
                "LEFT JOIN categories c ON c.id = pr.category_id " +
                "WHERE p.paid_at < ? " +
                "ORDER BY sold_at DESC, oi.id DESC";

        LocalDateTime safeThreshold = threshold == null ? LocalDateTime.now() : threshold;
        List<ProductSalesRow> rows = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, safeThreshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp soldAtTimestamp = rs.getTimestamp("sold_at");
                    LocalDateTime soldAt = soldAtTimestamp == null ? null : soldAtTimestamp.toLocalDateTime();
                    String name = rs.getString("product_name");
                    String category = rs.getString("category_name");
                    int qty = rs.getInt("quantity");
                    String paymentValue = rs.getString("payment_method");
                    PaymentMethod method = PaymentMethod.fromDatabaseValue(paymentValue);
                    BigDecimal amount = rs.getBigDecimal("amount_total");
                    rows.add(new ProductSalesRow(soldAt, name, category, qty, method, amount));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return rows;
    }
}
