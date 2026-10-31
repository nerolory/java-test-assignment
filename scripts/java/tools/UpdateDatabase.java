package tools;

import core.config.DatabaseConfig;
import core.database.DatabaseConnector;
import core.database.drivers.SqliteDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Явное обновление существующей SQLite-базы с резервной копией. */
public final class UpdateDatabase {
    private UpdateDatabase() { }

    /**
     * @param args корень проекта и необязательный путь к другой базе
     * @throws Exception если копирование или миграция не удались
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
        DatabaseConfig configured = DatabaseConfig.load(root.resolve("config/database.properties"), root);
        DatabaseConfig config = args.length > 1
                ? new DatabaseConfig("sqlite", Path.of(args[1]), configured.busyTimeoutMs()) : configured;
        if (!config.driver().equals("sqlite")) {
            throw new IllegalArgumentException("This migration supports SQLite only");
        }
        DatabaseConnector connector = new DatabaseConnector(new SqliteDriver().createDataSource(config));
        boolean migrated = connector.withConnection(connection -> {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(users)")) {
                boolean tableExists = false;
                while (rows.next()) {
                    tableExists = true;
                    if (rows.getString("name").equals("version")) {
                        return true;
                    }
                }
                if (!tableExists) {
                    throw new IllegalStateException("users table is missing; initialize schema.sql first");
                }
                return false;
            }
        });
        boolean sessionsExist = connector.withConnection(connection -> {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='auth_sessions'")) {
                return rows.next();
            }
        });
        if (migrated && sessionsExist) {
            System.out.println("All migrations already applied");
            return;
        }

        Path backup = config.path().resolveSibling(config.path().getFileName() + ".backup-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        // VACUUM INTO создаёт согласованную копию, включая данные из WAL.
        connector.withConnection(connection -> {
            try (var statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, backup.toString());
                statement.executeUpdate();
            }
            return null;
        });
        String versionMigration = Files.readString(root.resolve("database/migrations/001_users_version.sql"));
        String sessionMigration = Files.readString(root.resolve("database/migrations/002_auth_sessions.sql"));
        connector.inTransaction(connection -> {
            try (var statement = connection.createStatement()) {
                if (!migrated) {
                    statement.executeUpdate(versionMigration);
                }
                if (!sessionsExist) {
                    statement.executeUpdate(sessionMigration);
                }
            }
            return null;
        });
        System.out.println("Database migrations applied. Backup: " + backup);
    }
}
