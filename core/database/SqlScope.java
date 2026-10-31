package core.database;

import java.sql.SQLException;

/** Очистка настроек выполнения запроса на соединении. */
@FunctionalInterface
public interface SqlScope extends AutoCloseable {
    /**
     * Удаляет обработчики выполнения с соединения.
     *
     * @throws SQLException если настройки нельзя восстановить
     */
    @Override
    void close() throws SQLException;
}
