package tests.core;

import app.models.User;
import core.config.DatabaseConfig;
import core.database.Database;
import core.database.DatabaseConfigurator;
import core.database.DatabaseConnector;
import core.errors.DatabaseException;
import core.errors.ModelNotFoundException;
import core.database.drivers.SqliteDriver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Integration checks use an isolated temporary SQLite file, never database/app.db. */
public final class DatabaseTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path directory = Files.createTempDirectory("database-core-test-");
        Path file = Files.createFile(directory.resolve("test.db"));
        try {
            var config = new DatabaseConfig("sqlite", file, 1500);
            var connector = new DatabaseConnector(new SqliteDriver().createDataSource(config));
            Database db = DatabaseConfigurator.configure(config);
            connector.withConnection(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(Files.readString(root.resolve("database/schema.sql")));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                }
                return null;
            });

            var loaded = DatabaseConfig.load(root.resolve("config/database.properties"), root);
            check(loaded.path().equals(root.resolve("database/app.db")), "Config path resolution");
            expect(IllegalArgumentException.class, () -> DatabaseConfigurator.configure(new DatabaseConfig("mysql", file, 0)));
            expect(IllegalArgumentException.class, () -> new SqliteDriver().createDataSource(new DatabaseConfig("sqlite", directory.resolve("missing.db"), 0)));

            AtomicReference<Connection> borrowed = new AtomicReference<>();
            connector.withConnection(connection -> {
                borrowed.set(connection);
                try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA foreign_keys")) {
                    check(result.next() && result.getInt(1) == 1, "Foreign keys enabled");
                }
                return null;
            });
            check(borrowed.get().isClosed(), "Connection closed after success");
            expect(DatabaseException.class, () -> connector.withConnection(connection -> {
                borrowed.set(connection);
                throw new SQLException("Test SQL failure");
            }));
            check(borrowed.get().isClosed(), "Connection closed after SQL failure");
            expect(IllegalStateException.class, () -> connector.withConnection(connection -> {
                borrowed.set(connection);
                throw new IllegalStateException("Test runtime failure");
            }));
            check(borrowed.get().isClosed(), "Connection closed after runtime failure");

            String injection = "name'); DROP TABLE users; --";
            long id = db.table("users").insert(values(injection, "one@example.test"));
            check(id > 0, "Generated ID returned");
            check(db.table("users").where("id", id).first().orElseThrow().get("login").equals(injection), "Values parameterized");
            check(db.table("users").where("id", -1).first().isEmpty(), "Missing first is empty");
            expect(DatabaseException.class, () -> db.table("users").insert(values("other", "ONE@example.test")));
            expect(IllegalArgumentException.class, () -> db.table("users; DROP TABLE users"));
            expect(IllegalArgumentException.class, () -> db.table("users").where("id OR 1=1", 1));
            expect(IllegalStateException.class, () -> db.table("users").update(Map.of("login", "oops")));
            expect(IllegalStateException.class, () -> db.table("users").delete());
            var base = db.table("users");
            base.where("id", -1);
            check(base.get().size() == 1, "Builder is immutable");
            check(db.table("users").where("id", null).get().isEmpty(), "NULL predicate");
            check(db.table("users").where("id", id).update(Map.of("login", "updated")) == 1, "Update count");
            db.table("users").where("id", id).update(Map.of("updated_at", "2000-01-01 00:00:00"));
            db.table("users").where("id", id).update(Map.of("email", "updated@example.test"));
            check(!db.table("users").where("id", id).first().orElseThrow().get("updated_at").equals("2000-01-01 00:00:00"), "Timestamp trigger");

            long committed = db.transaction(tx -> tx.table("users").insert(values("committed", "commit@example.test")));
            check(db.table("users").where("id", committed).first().isPresent(), "Transaction committed");
            int count = db.table("users").get().size();
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                tx.table("users").insert(values("rolled-back", "rollback@example.test"));
                throw new IllegalStateException("Abort");
            }));
            check(db.table("users").get().size() == count, "Runtime exception rolls back");
            expect(DatabaseException.class, () -> db.transaction(tx -> {
                tx.table("users").insert(values("rolled-back-sql", "rollback-sql@example.test"));
                return tx.table("users").insert(values("duplicate", "commit@example.test"));
            }));
            check(db.table("users").get().size() == count, "SQL exception rolls back all operations");
            expect(IllegalStateException.class, () -> db.transaction(tx -> tx.transaction(nested -> null)));
            Database expired = db.transaction(tx -> tx);
            expect(IllegalStateException.class, () -> expired.table("users").get());

            User user = new User(db);
            user.setLogin("model-user");
            user.setEmail("model@example.test");
            user.setPasswordHash("test-encoded-hash");
            user.save();
            check(user.getId() != null && user.getCreatedAt() != null, "Model insert and timestamp");
            check(User.find(db, user.getId()).orElseThrow().getLogin().equals("model-user"), "Model lookup by id");
            check(User.on(db).where("email", "model@example.test").firstOrFail().getId().equals(user.getId()), "Typed firstOrFail");
            check(User.on(db).where("email", "missing@example.test").first().isEmpty(), "Typed empty first");
            check(User.on(db).where("id", -1).get().isEmpty(), "Typed empty list");
            expect(ModelNotFoundException.class, () -> User.on(db).where("id", -1).firstOrFail());
            check(User.on(db).get().size() == count + 1, "Query without conditions returns model list");
            var typedBase = User.on(db);
            typedBase.where("id", -1);
            check(typedBase.get().size() == count + 1, "Typed query remains immutable");
            User unsaved = new User(db);
            check(unsaved.getId() == null, "Unsaved model has no id");
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                User scoped = new User(tx);
                scoped.setLogin("typed-rollback");
                scoped.setEmail("typed-rollback@example.test");
                scoped.setPasswordHash("test-encoded-hash");
                scoped.save();
                check(User.on(tx).where("email", "typed-rollback@example.test").first().isPresent(), "Typed query uses transaction connection");
                throw new IllegalStateException("Abort typed transaction");
            }));
            check(User.on(db).where("email", "typed-rollback@example.test").first().isEmpty(), "Typed rollback persisted nothing");
            var escapedQuery = db.transaction(tx -> User.on(tx).where("id", id));
            expect(IllegalStateException.class, escapedQuery::get);
            User fetched = User.find(db, user.getId()).orElseThrow();
            check(fetched.getLogin().equals("model-user"), "Model maps row");
            fetched.setEmail("model-updated@example.test");
            fetched.save();
            check(User.find(db, fetched.getId()).orElseThrow().getEmail().equals("model-updated@example.test"), "Model update");
            long deleted = fetched.getId();
            fetched.delete();
            check(User.find(db, deleted).isEmpty() && fetched.getId() == null, "Model delete");
            expect(IllegalStateException.class, fetched::delete);
            check(db.table("users").allRows().delete() == count, "Explicit whole-table delete");
            System.out.println("Database integration checks passed: " + checks);
        } finally {
            // Fails if a connection or result set leaked and still locks the database on Windows.
            try (var files = Files.list(directory)) {
                for (Path entry : files.toList()) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }

    private static Map<String, Object> values(String login, String email) {
        Map<String, Object> values = new HashMap<>();
        values.put("login", login);
        values.put("email", email);
        values.put("password_hash", "test-encoded-hash");
        return values;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        checks++;
        try { work.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
