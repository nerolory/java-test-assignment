package core.database.drivers;

import core.database.SqlDialect;
import core.database.SqlScope;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteErrorCode;
import core.errors.DatabaseException;
import core.errors.UniqueConstraintException;

/** SQL SQLite и прерывание длительных вычислений. */
public final class SqliteDialect implements SqlDialect {
    /** Создаёт диалект без состояния. */
    public SqliteDialect() { }

    /** {@inheritDoc} */
    @Override
    public DatabaseException translate(SQLException error) {
        if (error instanceof SQLiteException sqlite
                && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
            return new UniqueConstraintException(error);
        }
        return SqlDialect.super.translate(error);
    }

    /** {@inheritDoc} */
    @Override
    public String quote(String name) {
        return '"' + name + '"';
    }

    /** {@inheritDoc} */
    @Override
    public String pagination(int limit, long offset) {
        return " LIMIT " + limit + " OFFSET " + offset;
    }

    /** {@inheritDoc} */
    @Override
    public SqlScope execution(Connection connection, int timeoutMs, BooleanSupplier cancelled) throws SQLException {
        SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        int originalTimeout = sqlite.getBusyTimeout();
        sqlite.setBusyTimeout(Math.min(originalTimeout, timeoutMs));

        // JDBC setQueryTimeout у этого драйвера ограничивает ожидание блокировки,
        // но не вычисления. ProgressHandler проверяется самим движком SQLite.
        try {
            ProgressHandler.setHandler(sqlite, 1000, new ProgressHandler() {
                @Override
                protected int progress() {
                    return cancelled.getAsBoolean() || System.nanoTime() >= deadline ? 1 : 0;
                }
            });
        } catch (SQLException error) {
            sqlite.setBusyTimeout(originalTimeout);
            throw error;
        }

        return () -> {
            try {
                ProgressHandler.clearHandler(sqlite);
            } finally {
                sqlite.setBusyTimeout(originalTimeout);
            }
        };
    }
}
