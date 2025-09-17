package state;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class OrderLogEntry {
    private final LocalDateTime timestamp;
    private final String message;

    public OrderLogEntry(LocalDateTime timestamp, String message) {
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
        this.message = message == null ? "" : message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getMessage() {
        return message;
    }

    public String formatForDisplay() {
        return String.format("%s - %s", timestamp.format(DateTimeFormatter.ofPattern("HH:mm")), message);
    }
}
