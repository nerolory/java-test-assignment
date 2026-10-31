package tools;

import core.config.DatabaseConfig;
import core.database.DatabaseConfigurator;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Создание новой SQLite-базы из database/schema.sql. Существующая база не изменяется. */
public final class CreateDatabase {
    private CreateDatabase() { }

    /**
     * @param args корень проекта и необязательный путь к другой базе
     * @throws Exception если база уже существует или схему не удалось применить
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        DatabaseConfig configured = DatabaseConfig.load(root.resolve("config/database.properties"), root);
        DatabaseConfig config = args.length > 1
                ? new DatabaseConfig("sqlite", Path.of(args[1]), configured.busyTimeoutMs()) : configured;
        try {
            DatabaseConfigurator.create(config, Files.readString(root.resolve("database/schema.sql")));
        } catch (FileAlreadyExistsException error) {
            throw new IllegalStateException("Database already exists: " + config.path()
                    + "; use scripts/migrate-database.ps1 to update it", error);
        }
        System.out.println("Database created: " + config.path());
    }
}
