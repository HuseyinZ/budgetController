package state;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class OrderLogEntry {
    private final LocalDateTime timestamp;
    private final String actor;
    private final String message;

    public OrderLogEntry(LocalDateTime timestamp, String message) {
        this(timestamp, null, message);
    }

    public OrderLogEntry(LocalDateTime timestamp, String actor, String message) {
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
        String[] parsed = parseMessage(actor, message);
        this.actor = parsed[0];
        this.message = parsed[1];
    }

    private String[] parseMessage(String actor, String message) {
        String resolvedActor = actor == null ? "" : actor.trim();
        String resolvedMessage = message == null ? "" : message.trim();
        if (!resolvedMessage.isEmpty() && resolvedActor.isEmpty()) {
            int sep = resolvedMessage.indexOf('|');
            if (sep >= 0) {
                resolvedActor = resolvedMessage.substring(0, sep).trim();
                resolvedMessage = resolvedMessage.substring(sep + 1).trim();
            }
        }
        return new String[]{resolvedActor, resolvedMessage};
    }

    public static OrderLogEntry fromRaw(LocalDateTime timestamp, String rawMessage) {
        return new OrderLogEntry(timestamp, null, rawMessage);
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getActor() {
        return actor;
    }

    public String getMessage() {
        return message;
    }

    public String formatForDisplay() {
        String time = timestamp.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (actor == null || actor.isBlank()) {
            return String.format("%s - %s", time, message);
        }
        return String.format("%s - %s %s", time, actor, message);
    }
}
