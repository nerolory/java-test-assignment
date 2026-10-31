package core.database;

import java.sql.Connection;
import java.sql.SQLException;
import core.errors.DatabaseException;
import java.util.function.BooleanSupplier;

/** Синтаксис SQL и управление выполнением для конкретной СУБД. */
public interface SqlDialect {
    /**
     * Переводит ошибку JDBC в общий тип ошибки ядра.
     * @param error ошибка драйвера
     * @return ошибка операции или более точный тип, известный диалекту
     */
    default DatabaseException translate(SQLException error) {
        return new DatabaseException("Database operation failed", error);
    }

    /**
     * Добавляет кавычки к проверенному имени.
     *
     * @param name проверенное имя
     * @return имя в кавычках СУБД
     */
    String quote(String name);

    /**
     * Ограничивает число и смещение строк.
     *
     * @param limit число строк
     * @param offset смещение
     * @return SQL ограничения выборки
     */
    String pagination(int limit, long offset);

    /**
     * Устанавливает контроль времени и отмены до закрытия результата.
     * @param connection соединение операции
     * @param timeoutMs время выполнения SQL
     * @param cancelled признак отмены
     * @return очистка установленных обработчиков
     * @throws SQLException если настройка не поддерживается
     */
    SqlScope execution(Connection connection, int timeoutMs, BooleanSupplier cancelled) throws SQLException;
}
