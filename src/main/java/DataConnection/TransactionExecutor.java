package DataConnection;

import java.sql.Connection;
import java.util.function.Function;

@FunctionalInterface
public interface TransactionExecutor {
    <T> T execute(Function<Connection, T> work);

    default void executeVoid(Function<Connection, ?> work) {
        execute(conn -> {
            work.apply(conn);
            return null;
        });
    }
}
