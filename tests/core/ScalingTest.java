package tests.core;

import core.errors.ResultLimitException;

import core.errors.OptimisticLockException;

import core.errors.DatabaseException;

import core.errors.DatabaseBusyException;

import app.models.User;
import tools.CreateDatabase;
import tools.UpdateDatabase;
import core.config.DatabaseConfig;
import core.config.DatabaseOptions;
import core.database.*;
import core.database.drivers.SqliteDriver;
import core.database.drivers.SqliteDialect;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;

/** Проверки конкуренции и ограничений на изолированной SQLite-базе. */
public final class ScalingTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("database-scaling-test-");
        Path file = Files.createFile(directory.resolve("test.db"));
        try {
            var options = new DatabaseOptions(2, 1, 100, 1000, 10, 20, 25);
            var config = new DatabaseConfig("sqlite", file, 500, options);
            DataSource source = new SqliteDriver().createDataSource(config);
            AtomicInteger opens = new AtomicInteger();
            DataSource counted = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getConnection")) opens.incrementAndGet();
                        try { return method.invoke(source, arguments); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            var connector = new DatabaseConnector(counted, options);
            Database db = new Database(connector, new SqliteDialect(), options);
            connector.withConnection(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(read("database/schema.sql"));
                }
                return null;
            });

            User original = user(db, "initial", "initial@example.test");
            opens.set(0);
            original.save();
            check(opens.get() == 1, "save uses one connection");
            check(original.getVersion() == 0 && original.getCreatedAt() != null, "insert defaults");
            long id = original.getId();
            User a = User.find(db, id).orElseThrow();
            User b = User.find(db, id).orElseThrow();
            a.setEmail("first@example.test");
            a.save();
            b.setLogin("stale");
            expect(OptimisticLockException.class, b::save);
            User current = User.find(db, id).orElseThrow();
            check(current.getEmail().equals("first@example.test") && current.getLogin().equals("initial"), "stale save does not lose changes");
            check(b.getVersion() == 0, "failed save keeps previous version");
            expect(OptimisticLockException.class, b::delete);
            current.save();
            check(current.getVersion() == 2, "unchanged save still advances version");
            db.table("users").where("id", id).update(Map.of("login", "raw-update"));
            expect(OptimisticLockException.class, current::save);
            check(User.find(db, id).orElseThrow().getVersion() == 3, "raw update also advances version");

            var prebuilt = User.on(db).where("login", "rolled-back");
            AtomicReference<User> rolled = new AtomicReference<>();
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                User created = user(db, "rolled-back", "rolled@example.test");
                rolled.set(created);
                created.save();
                check(prebuilt.exists(), "query created before transaction joins it");
                check(User.on(db).where("login", "rolled-back").exists(), "query built inside transaction joins it");
                throw new IllegalStateException("abort");
            }));
            check(!prebuilt.exists(), "default model save is rolled back");
            check(rolled.get().getId() == null && rolled.get().getCreatedAt() == null, "rollback restores generated fields");

            User existing = User.find(db, id).orElseThrow();
            long beforeVersion = existing.getVersion();
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                existing.setLogin("first-in-tx");
                existing.save();
                existing.setLogin("second-in-tx");
                existing.save();
                throw new IllegalStateException("abort");
            }));
            check(existing.getVersion() == beforeVersion, "multiple saves restore original version");
            check(User.find(db, id).orElseThrow().getLogin().equals("raw-update"), "pre-existing model joins transaction");
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                existing.delete();
                throw new IllegalStateException("abort");
            }));
            check(existing.getId().equals(id) && User.find(db, id).isPresent(), "delete rollback restores id and row");

            BrokenUser broken = new BrokenUser(db);
            long countBeforeFailure = db.table("users").count();
            expect(IllegalArgumentException.class, broken::save);
            check(db.table("users").count() == countBeforeFailure, "failed hydration rolls back insert");
            expect(IllegalStateException.class, () -> db.transaction(tx -> {
                try { broken.save(); } catch (IllegalArgumentException expected) { }
                db.table("users").insert(values("must-rollback", "must-rollback@example.test"));
                return null;
            }));
            check(!User.on(db).where("login", "must-rollback").exists(), "caught atomic failure still prevents commit");

            for (int start = 0; start < 45; start += 25) {
                List<Map<String, Object>> batch = new ArrayList<>();
                for (int i = start; i < Math.min(45, start + 25); i++) batch.add(values("batch-" + i, "batch-" + i + "@example.test"));
                db.table("users").insertAll(batch);
            }
            check(db.table("users").count() == 46, "batch count");
            expect(ResultLimitException.class, () -> User.on(db).get());
            expect(ResultLimitException.class, () -> db.table("users").get());
            check(User.on(db).limit(7).get().size() == 7, "explicit limit");
            check(User.on(db).page(2, 10).get().getFirst().getId() == 11, "stable second page");
            check(User.on(db).afterId(40).limit(20).get().getFirst().getId() == 41, "keyset page");
            check(db.table("users").select("id", "login").limit(1).get().getFirst().size() == 2, "projection");
            check(User.on(db).page(20, 10).count() == 46 && User.on(db).page(20, 10).exists(), "aggregates ignore page");
            check(User.on(db).where("id", -1).count() == 0 && !User.on(db).where("id", -1).exists(), "empty aggregates");
            expect(IllegalArgumentException.class, () -> User.on(db).limit(21));
            expect(IllegalArgumentException.class, () -> User.on(db).page(0, 10));
            expect(IllegalStateException.class, () -> db.table("users").limit(1).allRows().delete());
            expect(IllegalArgumentException.class, () -> db.table("users").select("email; DROP TABLE users"));
            expect(IllegalStateException.class, () -> User.on(db).orderBy("login").afterId(1));
            expect(IllegalStateException.class, () -> User.on(db).afterId(1).orderBy("login"));
            Set<Long> visited = new HashSet<>();
            User.on(db).chunkById(9, batch -> {
                check(batch.size() <= 9, "bounded chunk");
                for (User model : batch) check(visited.add(model.getId()), "no duplicate model in chunks");
            });
            check(visited.size() == 46, "chunks visit all models");
            expect(IllegalStateException.class, () -> User.on(db).limit(1).chunkById(2, batch -> { }));
            long beforeBatch = db.table("users").count();
            expect(DatabaseException.class, () -> db.table("users").insertAll(List.of(
                    values("partial", "partial@example.test"), values("duplicate", "first@example.test"))));
            check(db.table("users").count() == beforeBatch && !User.on(db).where("login", "partial").exists(), "batch failure is atomic");
            expect(IllegalArgumentException.class, () -> db.table("users").insertAll(Collections.nCopies(26, values("x", "x@test"))));

            // Две независимые сессии: только одна может сохранить одну прочитанную версию.
            User left = User.find(db, id).orElseThrow();
            Database other = DatabaseConfigurator.configure(config);
            other.transaction(tx -> {
                check(User.on(other).where("id", id).exists(), "explicit database query joins its own transaction");
                expect(IllegalStateException.class, () -> db.table("users").first());
                return null;
            });
            check(db.table("users").where("id", id).exists(), "other database usable after the transaction ends");
            User right = User.find(other, id).orElseThrow();
            CountDownLatch start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                Future<Boolean> first = workers.submit(() -> competingSave(left, "left", start));
                Future<Boolean> second = workers.submit(() -> competingSave(right, "right", start));
                start.countDown();
                check(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS), "exactly one concurrent version update succeeds");
            }

            testAdmission(source);
            testCancellation(db, connector, source);
            testPoolReset(source);
            testMigration(directory);
            testCreation(directory);
            check(db.statistics().queries() > 0 && db.statistics().failures() > 0, "SQL metrics available");
            System.out.println("Scaling checks passed: " + checks);
        } finally {
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }

    private static void testAdmission(DataSource source) throws Exception {
        var connector = new DatabaseConnector(source, new DatabaseOptions(1, 0, 50, 1000, 10, 20, 25));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> active = executor.submit(() -> connector.withConnection(connection -> {
                entered.countDown();
                await(release);
                return null;
            }));
            try {
                check(entered.await(3, TimeUnit.SECONDS), "connection acquired");
                expect(DatabaseBusyException.class, () -> connector.withConnection(connection -> null));
            } finally { release.countDown(); }
            active.get(3, TimeUnit.SECONDS);
        }
        check(connector.withConnection(connection -> 42) == 42, "capacity released");
        var queued = new DatabaseConnector(source, new DatabaseOptions(1, 1, 30, 1000, 10, 20, 25));
        queued.withConnection(connection -> {
            expect(DatabaseBusyException.class, () -> queued.withConnection(other -> null));
            return null;
        });
        check(queued.withConnection(connection -> 42) == 42, "capacity released after timeout");
    }

    private static void testCancellation(Database db, DatabaseConnector connector, DataSource source) throws Exception {
        connector.withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE VIEW slow AS WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<1000000000) SELECT sum(x) AS id FROM n");
            }
            return null;
        });
        var fast = DatabaseConfigurator.configure(source, new SqliteDialect(), new DatabaseOptions(1, 1, 100, 20, 10, 20, 25));
        long started = System.nanoTime();
        expect(DatabaseException.class, () -> fast.table("slow").first());
        check(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "CPU query deadline enforced");
        check(fast.table("users").exists(), "connection remains usable after timeout");
        CancellationToken token = new CancellationToken();
        token.cancel();
        expect(CancellationException.class, () -> db.withCancellation(token, () -> db.table("users").get()));

        var one = DatabaseConfigurator.configure(source, new SqliteDialect(), new DatabaseOptions(1, 1, 100, 5000, 10, 20, 25));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var tasks = new DatabaseTasks(one)) {
            Future<?> active = tasks.submit(() -> { entered.countDown(); await(release); return null; });
            try {
                check(entered.await(3, TimeUnit.SECONDS), "background task started");
                Future<?> queued = tasks.submit(() -> one.table("users").exists());
                expect(RejectedExecutionException.class, () -> tasks.submit(() -> 1));
                check(queued.cancel(true), "queued task cancellation");
            } finally { release.countDown(); }
            active.get(3, TimeUnit.SECONDS);
            Future<?> slow = tasks.submit(() -> one.table("slow").first());
            slow.cancel(true);
            expect(CancellationException.class, slow::get);
            check(tasks.submit(() -> one.table("users").exists()).get(3, TimeUnit.SECONDS), "worker usable after cancellation");
            CountDownLatch written = new CountDownLatch(1);
            Future<?> activeWrite = tasks.submit(() -> one.transaction(tx -> {
                tx.table("users").insert(values("cancelled-write", "cancelled-write@test"));
                written.countDown();
                return tx.table("slow").first();
            }));
            check(written.await(3, TimeUnit.SECONDS), "write completed before cancellation");
            check(activeWrite.cancel(false), "running SQL receives cooperative cancellation");
            check(!tasks.submit(() -> one.table("users").where("login", "cancelled-write").exists())
                    .get(3, TimeUnit.SECONDS), "cancelled transaction rolls back earlier write");
            one.transaction(tx -> {
                expect(IllegalStateException.class, () -> tasks.submit(() -> 1));
                return null;
            });
        }
    }

    private static void testPoolReset(DataSource source) throws Exception {
        // Обёртка имитирует выдачу одного физического соединения из внешнего пула.
        try (var physical = source.getConnection()) {
            var borrowed = (java.sql.Connection) Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("close")) return null;
                        try { return method.invoke(physical, arguments); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            DataSource pooled = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("getConnection")) return borrowed;
                        try { return method.invoke(source, arguments); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            DatabaseConnector connector = new DatabaseConnector(pooled);
            connector.inTransaction(connection -> null);
            check(physical.getAutoCommit(), "pooled connection reset after commit");
            expect(IllegalStateException.class, () -> connector.inTransaction(connection -> { throw new IllegalStateException("abort"); }));
            check(physical.getAutoCommit(), "pooled connection reset after rollback");
        }
    }

    private static void testMigration(Path directory) throws Exception {
        Path legacy = Files.createFile(directory.resolve("legacy.db"));
        var connector = new DatabaseConnector(new SqliteDriver().createDataSource(new DatabaseConfig("sqlite", legacy, 500)));
        connector.withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE users(id INTEGER PRIMARY KEY, login TEXT, email TEXT, password_hash TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP, updated_at TEXT DEFAULT CURRENT_TIMESTAMP); INSERT INTO users(login,email,password_hash) VALUES('legacy','legacy@test','hash')");
            }
            return null;
        });
        String root = Path.of("").toAbsolutePath().toString();
        UpdateDatabase.main(new String[]{root, legacy.toString()});
        UpdateDatabase.main(new String[]{root, legacy.toString()});
        Database migrated = DatabaseConfigurator.configure(new DatabaseConfig("sqlite", legacy, 500));
        check(User.find(migrated, 1).orElseThrow().getVersion() == 0, "migration preserves existing row");
        migrated.table("users").where("id", 1).update(Map.of("login", "migrated"));
        check(User.find(migrated, 1).orElseThrow().getVersion() == 1, "migration installs version trigger");
        try (var files = Files.list(directory)) {
            check(files.anyMatch(path -> path.getFileName().toString().startsWith("legacy.db.backup-")), "migration creates backup");
        }
    }

    private static void testCreation(Path directory) throws Exception {
        Path created = directory.resolve("created.db");
        String root = Path.of("").toAbsolutePath().toString();
        CreateDatabase.main(new String[]{root, created.toString()});
        Database fresh = DatabaseConfigurator.configure(new DatabaseConfig("sqlite", created, 500));
        check(fresh.table("users").count() == 0 && fresh.table("auth_sessions").count() == 0, "creation applies schema");
        fresh.table("users").insert(Map.of("login", "fresh", "email", "fresh@example.test", "password_hash", "hash"));
        try {
            CreateDatabase.main(new String[]{root, created.toString()});
            throw new AssertionError("creation must not overwrite existing database");
        } catch (IllegalStateException expected) {
            check(fresh.table("users").count() == 1, "existing database is left untouched");
        }
        check(!DatabaseConfigurator.createIfMissing(new DatabaseConfig("sqlite", created, 500), read("database/schema.sql"))
                && fresh.table("users").count() == 1, "createIfMissing reports an existing database without an exception");
        check(DatabaseConfigurator.createIfMissing(new DatabaseConfig("sqlite", directory.resolve("missing.db"), 500),
                read("database/schema.sql")), "createIfMissing creates an absent database");

        Path broken = directory.resolve("broken.db");
        try {
            DatabaseConfigurator.create(new DatabaseConfig("sqlite", broken, 500), "CREATE TABLE broken (");
            throw new AssertionError("invalid schema must fail");
        } catch (DatabaseException expected) {
            check(Files.notExists(broken), "failed creation leaves no database file");
        }

        String schema = read("database/schema.sql");
        Path raced = directory.resolve("raced.db");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                try {
                    DatabaseConfigurator.create(new DatabaseConfig("sqlite", raced, 500), schema);
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException lost) {
                    return false;
                }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            check(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS), "concurrent creation has one winner");
        }
        check(DatabaseConfigurator.configure(new DatabaseConfig("sqlite", raced, 500)).table("users").count() == 0,
                "concurrently created database is complete");

        Path stale = Files.createFile(directory.resolve("cleaned.db.1.creating"));
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofMinutes(5))));
        Path recent = Files.createFile(directory.resolve("cleaned.db.2.creating"));
        DatabaseConfigurator.create(new DatabaseConfig("sqlite", directory.resolve("cleaned.db"), 500), schema);
        check(Files.notExists(stale) && Files.exists(recent), "abandoned staging removed, recent one kept for a parallel creator");
        Files.delete(recent);

        Path folderTarget = Files.createDirectory(directory.resolve("folder.db"));
        expectPathError(() -> {
            DatabaseConfigurator.create(new DatabaseConfig("sqlite", folderTarget, 500), schema);
            return null;
        }, "directory in place of the database");
        Path fileParent = Files.createFile(directory.resolve("not-a-folder"));
        expectPathError(() -> {
            DatabaseConfigurator.create(new DatabaseConfig("sqlite", fileParent.resolve("app.db"), 500), schema);
            return null;
        }, "file in place of the database folder");
        try (var files = Files.list(directory)) {
            check(files.noneMatch(path -> path.getFileName().toString().endsWith(".creating")), "no staged files remain");
        }
    }

    /** Callers read FileAlreadyExistsException as "the database exists", so path problems must not use it. */
    private static void expectPathError(Callable<Void> work, String situation) throws Exception {
        try {
            work.call();
        } catch (java.nio.file.FileAlreadyExistsException wrong) {
            throw new AssertionError(situation + " reported as an existing database", wrong);
        } catch (java.nio.file.FileSystemException expected) {
            check(true, situation + " reported as a path error");
            return;
        }
        throw new AssertionError(situation + " must fail");
    }

    private static boolean competingSave(User user, String name, CountDownLatch start) {
        await(start);
        user.setLogin(name);
        try { user.save(); return true; }
        catch (OptimisticLockException expected) { return false; }
    }

    private static User user(Database db, String login, String email) {
        User user = new User(db);
        user.setLogin(login);
        user.setEmail(email);
        user.setPasswordHash("test-hash");
        return user;
    }

    private static Map<String, Object> values(String login, String email) {
        return Map.of("login", login, "email", email, "password_hash", "test-hash");
    }

    private static String read(String path) {
        try { return Files.readString(Path.of(path)); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Checked work) {
        checks++;
        try { work.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static final class BrokenUser extends Model {
        BrokenUser(Database database) { super(database); }
        @Override protected String tableName() { return "users"; }
        @Override protected Map<String, Object> attributes() { return values("broken", "broken@test"); }
        @Override protected void readAttributes(Map<String, Object> row) {
            if (row.get("id") != null) throw new IllegalArgumentException("Simulated hydration failure");
        }
    }
}
