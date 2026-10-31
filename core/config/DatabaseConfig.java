package core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Настройки подключения к SQLite.
 *
 * @param driver имя адаптера подключения
 * @param path путь к файлу базы; сохраняется абсолютным
 * @param busyTimeoutMs время ожидания блокировки в миллисекундах
 * @param options ограничения запросов и соединений
 */
public record DatabaseConfig(String driver, Path path, int busyTimeoutMs, DatabaseOptions options) {
    /**
     * Создаёт настройки с ограничениями по умолчанию.
     * @param driver имя адаптера
     * @param path файл SQLite
     * @param busyTimeoutMs ожидание блокировки
     */
    public DatabaseConfig(String driver, Path path, int busyTimeoutMs) {
        this(driver, path, busyTimeoutMs, DatabaseOptions.defaults());
    }
    /**
     * Проверяет обязательные значения, нормализует имя адаптера и абсолютный путь.
     *
     * @param driver имя адаптера
     * @param path путь к файлу базы
     * @param busyTimeoutMs неотрицательное время ожидания
     * @param options ограничения нагрузки
     * @throws IllegalArgumentException если обязательное значение отсутствует или время отрицательно
     */
    public DatabaseConfig {
        java.util.Objects.requireNonNull(options);
        if (driver == null || driver.isBlank()) {
            throw new IllegalArgumentException("Database driver is required");
        }

        driver = driver.trim().toLowerCase(Locale.ROOT);

        if (path == null) {
            throw new IllegalArgumentException("Database path is required");
        }

        path = path.toAbsolutePath().normalize();

        if (busyTimeoutMs < 0) {
            throw new IllegalArgumentException("Busy timeout must not be negative");
        }
    }

    /**
     * Читает UTF-8 properties-файл. Путь базы вычисляется относительно projectRoot.
     *
     * <p>Время ожидания блокировки по умолчанию — 5000 мс.
     *
     * @param file путь к файлу конфигурации
     * @param projectRoot корень проекта для разрешения относительных путей
     * @return проверенные настройки без открытия соединения
     * @throws java.io.IOException если файл нельзя прочитать
     * @throws IllegalArgumentException если настройки отсутствуют или имеют неверный формат
     */
    public static DatabaseConfig load(Path file, Path projectRoot) throws IOException {
        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        String driver = required(properties, "database.driver");
        String location = required(properties, "database.path");
        int timeout;

        try {
            timeout = Integer.parseInt(properties.getProperty("database.busy_timeout_ms", "5000").trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("database.busy_timeout_ms must be an integer", error);
        }

        DatabaseOptions defaults = DatabaseOptions.defaults();
        DatabaseOptions options = new DatabaseOptions(
                integer(properties, "database.max_connections", defaults.maxConnections()),
                integer(properties, "database.max_waiting", defaults.maxWaiting()),
                integer(properties, "database.acquire_timeout_ms", defaults.acquireTimeoutMs()),
                integer(properties, "database.query_timeout_ms", defaults.queryTimeoutMs()),
                integer(properties, "database.default_limit", defaults.defaultLimit()),
                integer(properties, "database.max_limit", defaults.maxLimit()),
                integer(properties, "database.max_batch", defaults.maxBatch()));
        return new DatabaseConfig(driver, projectRoot.resolve(location), timeout, options);
    }

    /**
     * Читает целое значение с настройкой по умолчанию.
     * @param properties свойства файла
     * @param key имя настройки
     * @param fallback значение по умолчанию
     * @return разобранное число
     */
    private static int integer(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    /**
     * Извлекает обязательное непустое свойство и убирает пробелы по краям.
     *
     * @param properties прочитанные свойства конфигурации
     * @param name ключ обязательного свойства
     * @return непустое значение без пробелов по краям
     * @throws IllegalArgumentException если свойство отсутствует или пустое
     */
    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + name);
        }

        return value.trim();
    }
}
