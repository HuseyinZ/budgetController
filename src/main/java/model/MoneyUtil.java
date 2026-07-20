package model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

public final class MoneyUtil {
    private MoneyUtil() {

    }
    public static BigDecimal two(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static <T> BigDecimal sumAmounts(List<T> items, Function<T, BigDecimal> amountExtractor) {
        BigDecimal total = BigDecimal.ZERO;
        for (T item : items) {
            BigDecimal amount = amountExtractor.apply(item);
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total;
    }
}
