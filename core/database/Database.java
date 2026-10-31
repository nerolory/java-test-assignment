package core.database;

import core.errors.DatabaseException;

import core.config.DatabaseOptions;
import core.database.drivers.SqliteDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Выполнение запросов и транзакций. Транзакция привязана к потоку и объекту БД. */
public final class Database {
    /** Источник ограниченных по числу соединений. */
    private final DatabaseConnector connector;
    /** Синтаксис и обработчики выбранной СУБД. */
    private final SqlDialect dialect;
    /** Ограничения нагрузки. */
    private final DatabaseOptions options;
    /** Основной объект БД. */
    private final Database root;
    /** Соединение транзакции или null. */
    private final Connection connection;
    /** Поток транзакции или null. */
    private final Thread thread;
    /** Активная транзакция основного объекта в текущем потоке. */
    private final ThreadLocal<Database> current;
    /** Сигнал отмены текущего сценария. */
    private final ThreadLocal<CancellationToken> cancellation;
    /** Восстановление состояния моделей при откате. */
    private final List<Runnable> rollbackActions = new ArrayList<>();
    /** Общие счётчики SQL-запросов. */
    private final DatabaseStatistics statistics;
    /** Признак действующей транзакционной сессии. */
    private boolean active = true;
    /** Запрет commit после неудачной составной операции. */
    private boolean rollbackOnly;

    /**
     * Создаёт объект для работы с соединениями.
     *
     * @param connector коннектор SQLite с ограничениями по умолчанию
     */
    public Database(DatabaseConnector connector) {
        this(connector, new SqliteDialect(), DatabaseOptions.defaults());
    }

    /**
     * Создаёт основной объект БД.
     * @param connector источник соединений
     * @param dialect особенности СУБД
     * @param options ограничения нагрузки
     */
    public Database(DatabaseConnector connector, SqlDialect dialect, DatabaseOptions options) {
        this.connector = Objects.requireNonNull(connector);
        this.dialect = Objects.requireNonNull(dialect);
        this.options = Objects.requireNonNull(options);
        root = this;
        connection = null;
        thread = null;
        current = new ThreadLocal<>();
        cancellation = new ThreadLocal<>();
        statistics = new DatabaseStatistics();
    }

    /**
     * Создаёт объект для работы с соединениями.
     *
     * @param root основной объект
     * @param connection соединение транзакции
     */
    private Database(Database root, Connection connection) {
        this.root = root;
        this.connection = connection;
        connector = root.connector;
        dialect = root.dialect;
        options = root.options;
        thread = Thread.currentThread();
        current = root.current;
        cancellation = root.cancellation;
        statistics = root.statistics;
    }

    /**
     * Создаёт запрос к таблице.
     *
     * @param table простое имя таблицы
     * @return новый запрос
     */
    public QueryBuilder table(String table) {
        return new QueryBuilder(session(), table);
    }

    /**
     * Возвращает ограничения нагрузки.
     *
     * @return ограничения запросов
     */
    public DatabaseOptions options() {
        return options;
    }

    /**
     * Возвращает счётчики SQL-операций.
     *
     * @return текущие счётчики без SQL и значений параметров
     */
    public DatabaseStatistics.Snapshot statistics() {
        return statistics.snapshot();
    }

    /**
     * Возвращает диалект запросов.
     *
     * @return диалект запросов
     */
    SqlDialect dialect() {
        return dialect;
    }

    /** Не допускает случайного выноса части транзакции в фоновый поток. */
    void checkAsyncBoundary() {
        if (session().connection != null) {
            throw new IllegalStateException("Submit the whole transaction as one background task");
        }
    }

    /**
     * Выбирает действующую сессию текущего потока.
     *
     * @return действующая сессия текущего потока или основной объект
     */
    private Database session() {
        TransactionContext.checkDatabase(root);
        if (connection != null) {
            if (!active || Thread.currentThread() != thread) {
                throw new IllegalStateException("Transaction scope has ended or is used from another thread");
            }
            return this;
        }
        Database scoped = current.get();
        return scoped == null ? this : scoped;
    }

    /**
     * Выполняет сценарий с сигналом отмены. Сигнал не переносится в другие потоки.
     * @param <T> тип результата
     * @param token сигнал отмены
     * @param work выполняемый сценарий
     * @return результат сценария
     */
    public <T> T withCancellation(CancellationToken token, Supplier<T> work) {
        Objects.requireNonNull(token);
        if (cancellation.get() != null) {
            throw new IllegalStateException("Cancellation scope is already active");
        }
        cancellation.set(token);
        try {
            token.check();
            return work.get();
        } finally {
            cancellation.remove();
        }
    }

    /**
     * Выполняет транзакцию. Основной объект в том же потоке присоединяется к ней.
     * Вложенный transaction запрещён; операции модели используют текущую сессию.
     * @param <T> тип результата
     * @param work операции транзакции
     * @return результат после commit
     */
    public <T> T transaction(Function<Database, T> work) {
        Objects.requireNonNull(work);
        if (session().connection != null) {
            throw new IllegalStateException("Nested transactions are not supported");
        }
        Database[] scoped = new Database[1];
        try {
            return connector.inTransaction(connection -> {
                Database tx = new Database(root, connection);
                scoped[0] = tx;
                TransactionContext.enter(root);
                current.set(tx);
                try {
                    T result = work.apply(tx);
                    checkCancellation();
                    if (tx.rollbackOnly) {
                        throw new IllegalStateException("Transaction is marked for rollback");
                    }
                    return result;
                } finally {
                    tx.active = false;
                    current.remove();
                    TransactionContext.leave();
                }
            });
        } catch (RuntimeException | Error error) {
            if (scoped[0] != null) {
                List<Runnable> actions = scoped[0].rollbackActions;
                for (int i = actions.size() - 1; i >= 0; i--) {
                    try {
                        actions.get(i).run();
                    } catch (RuntimeException restoreError) {
                        error.addSuppressed(restoreError);
                    }
                }
            }
            throw error;
        } finally {
            if (scoped[0] != null) {
                scoped[0].rollbackActions.clear();
            }
        }
    }

    /**
     * Присоединяет составную операцию к транзакции или открывает новую.
     * @param <T> тип результата
     * @param work составная операция
     * @return результат операции
     */
    <T> T atomic(Function<Database, T> work) {
        Database tx = session();
        if (tx.connection == null) {
            return transaction(work);
        }
        try {
            return work.apply(tx);
        } catch (RuntimeException | Error error) {
            tx.rollbackOnly = true;
            throw error;
        }
    }

    /**
     * Регистрирует восстановление модели при откате.
     *
     * @param action восстановление полей модели после отката
     */
    void onRollback(Runnable action) {
        Database tx = session();
        if (tx.connection == null) {
            throw new IllegalStateException("Rollback action requires a transaction");
        }
        tx.rollbackActions.add(action);
    }

    /** Проверяет отмену до обращения к драйверу и перед commit. */
    private void checkCancellation() {
        CancellationToken token = cancellation.get();
        if (token != null) {
            token.check();
        } else if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Database thread interrupted");
        }
    }

    /**
     * Проверяет запрос отмены.
     *
     * @return признак отмены без выбрасывания исключения
     */
    private boolean cancelled() {
        CancellationToken token = cancellation.get();
        return Thread.currentThread().isInterrupted() || token != null && token.isCancelled();
    }

    /**
     * Выполняет SQL с контролем времени и очисткой обработчика драйвера.
     * @param <T> тип результата
     * @param work операция JDBC
     * @return результат операции
     */
    private <T> T execute(SqlWork<T> work) {
        Database tx = session();
        checkCancellation();
        long started = System.nanoTime();
        boolean success = false;
        SqlWork<T> guarded = connection -> {
            checkCancellation();
            try (SqlScope scope = dialect.execution(connection, options.queryTimeoutMs(), this::cancelled)) {
                T result = work.execute(connection);
                checkCancellation();
                return result;
            } catch (SQLException error) {
                throw dialect.translate(error);
            }
        };
        try {
            T result = tx.connection == null
                    ? connector.withConnection(guarded) : guarded.execute(tx.connection);
            success = true;
            return result;
        } catch (SQLException error) {
            throw new DatabaseException("Database operation failed", error);
        } finally {
            statistics.record(System.nanoTime() - started, success);
        }
    }

    /**
     * Читает строки и сразу преобразует их в конечные объекты.
     * @param <T> тип результата
     * @param sql запрос с параметрами
     * @param parameters значения параметров
     * @param mapper преобразование строки без дополнительных запросов
     * @return неизменяемый список результатов
     */
    <T> List<T> select(String sql, List<Object> parameters, Function<Map<String, Object>, T> mapper) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (var result = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    var metadata = result.getMetaData();
                    int columns = metadata.getColumnCount();
                    while (result.next()) {
                        checkCancellation();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int index = 1; index <= columns; index++) {
                            row.put(metadata.getColumnLabel(index), result.getObject(index));
                        }
                        rows.add(mapper.apply(Collections.unmodifiableMap(row)));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    /**
     * Выполняет SELECT.
     *
     * @param sql запрос
     * @param parameters значения
     * @return строки результата
     */
    List<Map<String, Object>> select(String sql, List<Object> parameters) {
        return select(sql, parameters, Function.identity());
    }

    /**
     * Выполняет UPDATE или DELETE.
     *
     * @param sql запрос
     * @param parameters значения
     * @return число изменённых строк
     */
    int modify(String sql, List<Object> parameters) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Вставляет запись и получает её ключ.
     *
     * @param sql INSERT
     * @param parameters значения
     * @return созданный целочисленный ключ
     */
    long insert(String sql, List<Object> parameters) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(statement, parameters);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Insert returned no generated key");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    /**
     * Выполняет ограниченный пакет в одной транзакции без получения ключей.
     * @param sql INSERT с одинаковыми колонками
     * @param rows значения каждой строки
     */
    void insertBatch(String sql, List<List<Object>> rows) {
        atomic(tx -> tx.execute(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (List<Object> row : rows) {
                    checkCancellation();
                    bind(statement, row);
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                for (int count : counts) {
                    if (count == Statement.EXECUTE_FAILED) {
                        throw new SQLException("Batch statement failed");
                    }
                }
                return null;
            }
        }));
    }

    /**
     * Подставляет параметры в порядке колонок и условий.
     * @param statement подготовленный запрос
     * @param parameters значения параметров
     * @throws SQLException если привязка не удалась
     */
    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }
}
