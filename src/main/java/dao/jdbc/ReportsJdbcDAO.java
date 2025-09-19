package dao.jdbc;

import DataConnection.Db;
import dao.ReportsDAO;
import model.ProductSalesRow;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String sql = "SELECT oi.product_name, " +
                "SUM(oi.quantity) AS qty_total, " +
                "SUM(COALESCE(oi.line_total, oi.quantity * oi.unit_price * 1.20)) AS amount_total " +
                "FROM payments p " +
                "JOIN orders o ON o.id = p.order_id " +
                "JOIN order_items oi ON oi.order_id = o.id " +
                "WHERE p.paid_at < ? " +
                "GROUP BY oi.product_name " +
                "ORDER BY amount_total DESC";

        LocalDateTime safeThreshold = threshold == null ? LocalDateTime.now() : threshold;
        List<ProductSalesRow> rows = new ArrayList<>();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, safeThreshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("product_name");
                    int qty = rs.getInt("qty_total");
                    BigDecimal amount = rs.getBigDecimal("amount_total");
                    rows.add(new ProductSalesRow(name, qty, amount));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return rows;
    }
}
