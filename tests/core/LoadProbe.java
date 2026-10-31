package tests.core;

import core.errors.OptimisticLockException;

import core.errors.DatabaseException;

import app.models.User;
import core.config.DatabaseConfig;
import core.database.*;
import core.database.drivers.SqliteDriver;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Диагностическая нагрузка на временную БД; не оценка производственной ёмкости. */
public final class LoadProbe {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("worker")) {
            worker(args);
            return;
        }
        Path directory = Files.createTempDirectory("database-load-probe-");
        Path file = Files.createFile(directory.resolve("load.db"));
        try {
            var config = new DatabaseConfig("sqlite", file, 1000);
            var connector = new DatabaseConnector(new SqliteDriver().createDataSource(config));
            connector.withConnection(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(read(Path.of("database/schema.sql")));
                }
                return null;
            });
            Database db = DatabaseConfigurator.configure(config);
            for (int start = 0; start < 1000; start += 500) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = start; i < start + 500; i++) {
                    rows.add(Map.of("login", "load-" + i, "email", "load-" + i + "@test", "password_hash", "test-hash"));
                }
                db.table("users").insertAll(rows);
            }
            twoProcesses(file, directory);
            for (int i = 0; i < 50; i++) User.find(db, i + 1).orElseThrow();
            System.out.println("Local SQLite diagnostic: 1000 rows, 80% reads / 20% model updates, default rollback journal");
            System.out.println("workers,operations,ops_per_second,p95_ms,p99_ms,conflicts,errors,heap_used_MiB");
            for (int workers : new int[]{1, 2, 4, 8}) measure(db, workers);
        } finally {
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) Files.delete(entry);
            }
            Files.delete(directory);
        }
    }

    private static void measure(Database db, int workers) throws Exception {
        int operations = workers * 50;
        long[] elapsed = new long[operations];
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        long started;
        try (var executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int index = worker;
                tasks.add(executor.submit(() -> {
                    await(start);
                    for (int n = 0; n < 50; n++) {
                        long before = System.nanoTime();
                        long id = 1 + (index * 97 + n * 13) % 1000;
                        try {
                            User user = User.find(db, id).orElseThrow();
                            if (n % 5 == 0) {
                                user.setLogin("changed-" + workers + "-" + id + "-" + n);
                                user.save();
                            }
                        } catch (OptimisticLockException expected) {
                            conflicts.incrementAndGet();
                        } catch (DatabaseException error) {
                            errors.incrementAndGet();
                        }
                        elapsed[index * 50 + n] = System.nanoTime() - before;
                    }
                }));
            }
            started = System.nanoTime();
            start.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        Arrays.sort(elapsed);
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.printf(Locale.ROOT, "%d,%d,%.1f,%.3f,%.3f,%d,%d,%.1f%n", workers, operations,
                operations / seconds, elapsed[(int)Math.ceil(operations * .95) - 1] / 1_000_000.0,
                elapsed[(int)Math.ceil(operations * .99) - 1] / 1_000_000.0,
                conflicts.get(), errors.get(), heap / 1048576.0);
        if (errors.get() != 0) throw new AssertionError("Load probe encountered database errors");
    }

    private static void twoProcesses(Path file, Path directory) throws Exception {
        Path gate = directory.resolve("start");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<Process> children = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                children.add(new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                        System.getProperty("java.class.path"), LoadProbe.class.getName(), "worker",
                        file.toString(), gate.toString(), Integer.toString(i))
                        .redirectErrorStream(true).redirectOutput(directory.resolve("worker-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(directory.resolve("ready-0")) || !Files.exists(directory.resolve("ready-1"))) {
                if (System.nanoTime() > deadline || children.stream().anyMatch(p -> !p.isAlive())) {
                    throw new AssertionError("Child process did not reach barrier");
                }
                Thread.sleep(10);
            }
            Files.createFile(gate);
            for (Process child : children) {
                if (!child.waitFor(10, TimeUnit.SECONDS) || child.exitValue() != 0) {
                    throw new AssertionError("Child process failed");
                }
            }
            String result = read(directory.resolve("worker-0.log")) + read(directory.resolve("worker-1.log"));
            if (result.lines().filter("SAVED"::equals).count() != 1 || result.lines().filter("CONFLICT"::equals).count() != 1) {
                throw new AssertionError("Unexpected concurrent outcome: " + result);
            }
            System.out.println("Two JVM processes: one update committed, one version conflict; no lost update");
        } finally {
            for (Process child : children) {
                if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void worker(String[] args) throws Exception {
        Database db = DatabaseConfigurator.configure(new DatabaseConfig("sqlite", Path.of(args[1]), 2000));
        User user = User.find(db, 1).orElseThrow();
        Path gate = Path.of(args[2]);
        Files.createFile(gate.resolveSibling("ready-" + args[3]));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(gate)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Barrier timeout");
            Thread.sleep(10);
        }
        user.setLogin("process-" + args[3]);
        try {
            user.save();
            System.out.println("SAVED");
        } catch (OptimisticLockException expected) {
            System.out.println("CONFLICT");
        }
    }

    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }
}
