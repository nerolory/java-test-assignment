package core.database;

import core.config.DatabaseConfig;
import core.database.drivers.DatabaseDriver;
import core.database.drivers.SqliteDriver;
import core.database.drivers.SqliteDialect;
import core.config.DatabaseOptions;
import java.io.IOException;
import javax.sql.DataSource;

/**
 * Создание объекта БД по настройкам подключения.
 */
public final class DatabaseConfigurator {
    /**
     * Закрытый конструктор статического класса.
     */
    private DatabaseConfigurator() {
    }

    /**
     * Выбирает драйвер и создаёт объект БД.
     *
     * @param config проверенные настройки подключения
     * @return объект БД
     * @throws IllegalArgumentException если драйвер не поддержан или настройки неверны
     */
    public static Database configure(DatabaseConfig config) {
        return configure(driver(config).createDataSource(config), new SqliteDialect(), config.options());
    }

    /**
     * Создаёт новую базу по схеме. Существующая база не изменяется.
     *
     * @param config настройки подключения к новой базе
     * @param schema SQL создания таблиц
     * @throws java.nio.file.FileAlreadyExistsException если база уже существует или создана параллельно
     * @throws IOException если файл базы нельзя создать
     * @throws IllegalArgumentException если драйвер не поддержан
     */
    public static void create(DatabaseConfig config, String schema) throws IOException {
        driver(config).create(config, schema);
    }

    /**
     * Создаёт базу по схеме, если её ещё нет, и убирает остатки прерванных попыток.
     *
     * @param config настройки подключения
     * @param schema SQL создания таблиц
     * @return true, если база создана этим вызовом
     * @throws IOException если файл базы нельзя создать
     * @throws IllegalArgumentException если драйвер не поддержан
     */
    public static boolean createIfMissing(DatabaseConfig config, String schema) throws IOException {
        return driver(config).createIfMissing(config, schema);
    }

    /**
     * Выбирает адаптер по имени драйвера.
     *
     * @param config настройки подключения
     * @return адаптер СУБД
     */
    private static DatabaseDriver driver(DatabaseConfig config) {
        return switch (config.driver()) {
            case "sqlite" -> new SqliteDriver();
            default -> throw new IllegalArgumentException("Unsupported database driver: " + config.driver());
        };
    }

    /**
     * Собирает БД с внешним источником, включая пул соединений.
     * @param source источник, закрываемый приложением после завершения работы
     * @param dialect синтаксис и управление запросами выбранной СУБД
     * @param options ограничения одного экземпляра приложения
     * @return настроенный объект БД
     */
    public static Database configure(DataSource source, SqlDialect dialect, DatabaseOptions options) {
        return new Database(new DatabaseConnector(source, options), dialect, options);
    }
}
