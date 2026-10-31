package core.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Функция, выполняющая операцию через JDBC-соединение.
 *
 * @param <T> тип результата
 */
@FunctionalInterface
public interface SqlWork<T> {
    /**
     * Выполняет операцию через переданное соединение.
     *
     * @param connection соединение текущей операции
     * @return результат операции
     * @throws java.sql.SQLException если JDBC-операция завершилась ошибкой
     */
    T execute(Connection connection) throws SQLException;
}
