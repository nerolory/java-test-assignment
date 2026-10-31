package core.database;

import core.errors.DatabaseException;

import core.errors.DatabaseBusyException;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import core.config.DatabaseOptions;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;

/**
 * Открытие соединений, их закрытие и выполнение транзакций.
 */
public final class DatabaseConnector {
    /**
     * Источник JDBC-соединений.
     */
    private final DataSource source;

    /** Число одновременно используемых соединений. */
    private final Semaphore connections;

    /** Свободные места для работающих и ожидающих операций. */
    private final Semaphore capacity;

    /** Время ожидания соединения в миллисекундах. */
    private final int acquireTimeoutMs;

    /**
     * Сохраняет источник соединений.
     *
     * @param source настроенный источник соединений
     */
    public DatabaseConnector(DataSource source) {
        this(source, DatabaseOptions.defaults());
    }

    /**
     * Принимает обычный или пуловый DataSource. Его жизненным циклом управляет приложение.
     * @param source источник соединений
     * @param options ограничения нагрузки
     */
    public DatabaseConnector(DataSource source, DatabaseOptions options) {
        this.source = Objects.requireNonNull(source);
        this.connections = new Semaphore(options.maxConnections(), true);
        this.capacity = new Semaphore(options.maxConnections() + options.maxWaiting(), true);
        this.acquireTimeoutMs = options.acquireTimeoutMs();
    }

    /**
     * Открывает соединение, выполняет функцию и закрывает соединение.
     *
     * <p>Функция не должна возвращать открытые ResultSet, Statement или Connection.
     *
     * @param <T> тип результата
     * @param work функция с операциями БД
     * @return результат функции
     * @throws DatabaseException при ошибке получения, использования или закрытия соединения
     */
    public <T> T withConnection(SqlWork<T> work) {
        Objects.requireNonNull(work);

        if (!capacity.tryAcquire()) {
            throw new DatabaseBusyException("Database queue is full");
        }

        boolean acquired = false;
        try {
            acquired = connections.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new DatabaseBusyException("Timed out waiting for database capacity");
            }

            try (var connection = source.getConnection()) {
                if (!connection.getAutoCommit()) {
                    throw new SQLException("DataSource must supply an idle auto-commit connection");
                }
                return work.execute(connection);
            } catch (SQLException error) {
                throw new DatabaseException("Database operation failed", error);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for database capacity");
        } finally {
            if (acquired) {
                connections.release();
            }
            capacity.release();
        }
    }

    /**
     * Выполняет функцию в транзакции: сохраняет изменения при успехе, откатывает при ошибке.
     *
     * @param <T> тип результата
     * @param work функция с операциями БД
     * @return результат функции после сохранения изменений
     * @throws DatabaseException при ошибке JDBC
     */
    public <T> T inTransaction(SqlWork<T> work) {
        Objects.requireNonNull(work);
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            Throwable failure = null;
            boolean finished = false;

            try {
                T result = work.execute(connection);

                connection.commit();
                finished = true;
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                failure = error;
                try {
                    connection.rollback();
                    finished = true;
                } catch (SQLException rollbackError) {
                    // Сохраняем обе ошибки, оставляя исходную основной.
                    error.addSuppressed(rollbackError);
                }

                throw error;
            } finally {
                // После неудачного rollback нельзя включать auto-commit: это может
                // сохранить незавершённые изменения. Соединение будет закрыто.
                if (finished) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException resetError) {
                        if (failure != null) {
                            failure.addSuppressed(resetError);
                        } else {
                            throw resetError;
                        }
                    }
                }
            }
        });
    }
}
