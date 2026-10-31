package core.database.drivers;

import core.config.DatabaseConfig;
import core.database.DatabaseConnector;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Настройка SQLite JDBC: подключение к существующей базе и явное создание новой.
 */
public final class SqliteDriver implements DatabaseDriver {
    /** Возраст, после которого временный файл прерванного создания считается брошенным. */
    private static final Duration STALE_STAGING = Duration.ofMinutes(1);

    /**
     * Создаёт адаптер без состояния и без открытия соединения.
     */
    public SqliteDriver() {
    }

    /**
     * Создаёт настроенный источник JDBC-соединений для выбранной СУБД.
     *
     * @param config проверенные настройки подключения
     * @return настроенный источник соединений
     * @throws IllegalArgumentException если параметры подключения не подходят адаптеру
     */
    @Override
    public DataSource createDataSource(DatabaseConfig config) {
        // Опечатка в пути не должна приводить к созданию другой пустой базы.
        if (!Files.isRegularFile(config.path())) {
            throw new IllegalArgumentException("SQLite database does not exist: " + config.path());
        }

        SQLiteConfig options = new SQLiteConfig();
        options.enforceForeignKeys(true);
        options.setBusyTimeout(config.busyTimeoutMs());
        options.setOpenMode(org.sqlite.SQLiteOpenMode.READWRITE);
        // Отключаем автоматическое создание файла и на уровне самого драйвера.
        options.resetOpenMode(org.sqlite.SQLiteOpenMode.CREATE);

        SQLiteDataSource source = new SQLiteDataSource(options);
        source.setUrl("jdbc:sqlite:" + config.path());

        return source;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Сначала удаляет временные файлы прерванных попыток, поэтому вызов для существующей
     * базы тоже полезен: собранное приложение делает его при каждом запуске.
     */
    @Override
    public boolean createIfMissing(DatabaseConfig config, String schema) throws IOException {
        Path target = config.path();
        try {
            Files.createDirectories(target.getParent());
        } catch (FileAlreadyExistsException error) {
            // На месте папки лежит файл. FileAlreadyExistsException означает «база уже есть», поэтому другой тип.
            throw (NotDirectoryException) new NotDirectoryException(error.getFile()).initCause(error);
        }
        deleteStaleStaging(target);
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target)) {
                throw new FileSystemException(target.toString(), null, "Database path is not a regular file");
            }
            return false;
        }
        // Схема применяется во временном файле. Прерванное создание не оставит базу без таблиц.
        Path staged = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".creating");
        try {
            DatabaseConfig stagedConfig = new DatabaseConfig(config.driver(), staged, config.busyTimeoutMs(), config.options());
            new DatabaseConnector(createDataSource(stagedConfig)).inTransaction(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(schema);
                }
                return null;
            });
            try {
                // Переименование без REPLACE_EXISTING: базу, созданную параллельно, не перезаписываем.
                Files.move(staged, target);
                return true;
            } catch (FileAlreadyExistsException createdInParallel) {
                // Параллельный процесс успел первым, его база готова целиком; наша копия не нужна.
                Files.deleteIfExists(staged);
                return false;
            }
        } catch (IOException | RuntimeException | Error error) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    /**
     * Удаляет временные файлы прерванных попыток создания. Файлы моложе минуты может прямо
     * сейчас заполнять параллельный процесс, поэтому они остаются до следующего вызова.
     *
     * @param target путь создаваемой базы
     */
    private static void deleteStaleStaging(Path target) {
        String prefix = target.getFileName() + ".";
        Instant cutoff = Instant.now().minus(STALE_STAGING);
        try (var files = Files.newDirectoryStream(target.getParent(), file -> {
            String name = file.getFileName().toString();
            return name.startsWith(prefix) && name.contains(".creating");
        })) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException busy) {
                    // Файл занят или уже удалён другим процессом: попробуем при следующем вызове.
                }
            }
        } catch (IOException unreadable) {
            // Уборка не должна мешать созданию базы.
        }
    }
}
