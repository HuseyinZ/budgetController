package model;

import java.util.Locale;

public enum PaymentMethod {
    CASH("CASH"),
    CREDIT_CARD("CREDIT_CARD"),
    CARD("CREDIT_CARD"),
    DEBIT_CARD("DEBIT_CARD"),
    TRANSFER("TRANSFER"),
    ONLINE("ONLINE"),
    MIXED("MIXED");

    private final String databaseValue;

    PaymentMethod(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String getDatabaseValue() {
        return databaseValue;
    }

    public PaymentMethod canonical() {
        return this == CARD ? CREDIT_CARD : this;
    }

    public static PaymentMethod fromDatabaseValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        for (PaymentMethod method : values()) {
            if (method.databaseValue.equals(normalized) || method.name().equals(normalized)) {
                return method.canonical();
            }
        }
        return null;
    }
}
