package core.database.drivers;

import core.config.DatabaseConfig;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import javax.sql.DataSource;

/**
 * Контракт адаптера над JDBC-драйвером конкретной СУБД.
 */
public interface DatabaseDriver {
    /**
     * Создаёт настроенный источник JDBC-соединений для выбранной СУБД.
     *
     * @param config проверенные настройки подключения
     * @return настроенный источник соединений
     * @throws IllegalArgumentException если параметры подключения не подходят адаптеру
     */
    DataSource createDataSource(DatabaseConfig config);

    /**
     * Создаёт базу по схеме, если её ещё нет. База появляется только целиком.
     *
     * @param config настройки подключения
     * @param schema SQL создания таблиц
     * @return true, если база создана этим вызовом; false, если она уже была или её создал параллельный процесс
     * @throws IOException если файл базы нельзя создать, в том числе если на месте папки или базы
     *         лежит объект другого типа
     */
    boolean createIfMissing(DatabaseConfig config, String schema) throws IOException;

    /**
     * Создаёт новую базу. Для явной команды «создать» существующая база — ошибка.
     *
     * @param config настройки подключения к новой базе
     * @param schema SQL создания таблиц
     * @throws FileAlreadyExistsException если база уже существует или создана параллельно
     * @throws IOException если файл базы нельзя создать
     */
    default void create(DatabaseConfig config, String schema) throws IOException {
        if (!createIfMissing(config, schema)) {
            throw new FileAlreadyExistsException(config.path().toString());
        }
    }
}
