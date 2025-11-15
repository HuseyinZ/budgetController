package state;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class OrderLogEntry {
    public static final String ACTOR_SEPARATOR = " :: ";

    private final LocalDateTime timestamp;
    private final String rawMessage;
    private final String actor;
    private final String action;

    public OrderLogEntry(LocalDateTime timestamp, String message) {
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
        this.rawMessage = message == null ? "" : message;
        ParsedLog parsed = parse(rawMessage);
        this.actor = parsed.actor;
        this.action = parsed.action;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getMessage() {
        return rawMessage;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp.format(DateTimeFormatter.ofPattern("HH:mm")));
        if (!actor.isBlank()) {
            sb.append(" - ").append(actor);
        }
        if (!action.isBlank()) {
            sb.append(" - ").append(action);
        }
        return sb.toString();
    }

    private ParsedLog parse(String message) {
        String trimmed = message == null ? "" : message.trim();
        int idx = trimmed.indexOf(ACTOR_SEPARATOR);
        if (idx < 0) {
            return new ParsedLog("", trimmed);
        }
        String actorPart = trimmed.substring(0, idx).trim();
        String actionPart = trimmed.substring(idx + ACTOR_SEPARATOR.length()).trim();
        if (actionPart.isEmpty()) {
            actionPart = trimmed;
        }
        return new ParsedLog(Objects.toString(actorPart, ""), Objects.toString(actionPart, ""));
    }

    private record ParsedLog(String actor, String action) {}
}
