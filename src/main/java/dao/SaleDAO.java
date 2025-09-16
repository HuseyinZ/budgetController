package dao;

import model.Sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Read-only reporting (Sale = projection)
public interface SaleDAO {
    List<Sale> listSalesBetween(LocalDate start, LocalDate end, int offset, int limit);
    BigDecimal revenueBetween(LocalDate start, LocalDate end);
}
