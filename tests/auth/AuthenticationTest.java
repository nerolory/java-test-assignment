package tests.auth;

import app.contracts.AuthenticationService.*;
import app.contracts.SessionService.AuthenticatedUser;
import app.models.User;
import app.validation.LoginValidator;
import app.contracts.*;
import app.repositories.UserRepository;
import app.repositories.DatabaseSessionRepository;
import app.services.*;
import core.config.DatabaseConfig;
import core.contracts.SecretStore;
import core.database.*;
import core.database.drivers.SqliteDriver;
import core.security.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Проверки входа используют временную БД и не создают пользователей в приложении. */
public final class AuthenticationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("authentication-test-");
        try {
            Path file = Files.createFile(directory.resolve("test.db"));
            DatabaseConfig config = new DatabaseConfig("sqlite", file, 1500);
            DatabaseConnector connector = new DatabaseConnector(new SqliteDriver().createDataSource(config));
            String schema = Files.readString(Path.of("database/schema.sql"));
            connector.withConnection(connection -> {
                try (var statement = connection.createStatement()) { statement.executeUpdate(schema); }
                return null;
            });
            Database db = DatabaseConfigurator.configure(config);
            Pbkdf2PasswordHasher passwords = new Pbkdf2PasswordHasher();
            String knownHash = "pbkdf2-sha256$600000$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=$"
                    + "yeVvFHfLOCazln+e9luOwmQAqjKW8EHU49JrJdC9SdM=";
            check(passwords.verify("known-password".toCharArray(), knownHash), "Independent Python hashlib test vector");
            char[] password = " test-password-Я! ".toCharArray();
            String encoded = passwords.hash(password);
            check(passwords.verify(password, encoded), "Password roundtrip including spaces and Unicode");
            check(!passwords.verify("test-password-Я!".toCharArray(), encoded), "Password must not be trimmed");
            check(!passwords.hash(password).equals(encoded), "Random salts");
            for (String invalid : List.of("plaintext", "pbkdf2-sha256$2147483647$a$b", "pbkdf2-sha256$600000$!$!")) {
                check(!passwords.verify(password, invalid), "Invalid hash rejected");
            }
            long id = db.table("users").insert(Map.of("login", "Alice", "email", "alice@example.test", "password_hash", encoded));
            var repository = new UserRepository(db);
            var clock = new TestClock();
            var secrets = new MemorySecrets();
            var sessions = session(repository, db, secrets, clock);
            var service = new DefaultAuthenticationService(repository, passwords, sessions, new LoginValidator(),
                    new LoginAttemptLimiter(clock));

            var limiter = new LoginAttemptLimiter(clock);
            for (int i = 0; i < 5; i++) {
                check(limiter.reserve("carol").isEmpty(), "Attempts within account limit allowed");
            }
            check(limiter.reserve("carol").orElseThrow().equals(clock.now.plus(Duration.ofMinutes(1))), "Sixth attempt throttled until window end");
            check(limiter.reserve("CAROL").isPresent(), "Login case does not bypass account limit");
            limiter.succeeded("Carol");
            check(limiter.reserve("carol").isEmpty(), "Successful login resets account counter");
            for (int i = 0; i < 24; i++) {
                check(limiter.reserve("user-" + i).isEmpty(), "Other accounts allowed below global limit");
            }
            check(limiter.reserve("another").isPresent(), "Global limit applies across accounts");
            clock.now = clock.now.plus(Duration.ofMinutes(1));
            check(limiter.reserve("carol").isEmpty() && limiter.reserve("another").isEmpty(), "Limits reset after the window");

            var exhausted = new LoginAttemptLimiter(clock);
            for (int i = 0; i < 5; i++) {
                exhausted.reserve("alice");
            }
            var throttled = new DefaultAuthenticationService(repository, passwords, sessions, new LoginValidator(), exhausted);
            check(throttled.authenticate(new LoginRequest("Alice", password)) instanceof AuthenticationResult.Throttled,
                    "Throttled login skips password check even with the correct password");
            check(sessions.current().isEmpty() && db.table("auth_sessions").count() == 0, "Throttled login creates no session");

            var flaky = new AuthenticationRepository() {
                int failures = 6;
                public Optional<User> findByLogin(String login) {
                    if (failures-- > 0) { throw new core.errors.DatabaseException("Test outage", null); }
                    return repository.findByLogin(login);
                }
                public Optional<User> findById(long userId) { return repository.findById(userId); }
            };
            var resilient = new DefaultAuthenticationService(flaky, passwords, sessions, new LoginValidator(),
                    new LoginAttemptLimiter(clock));
            for (int i = 0; i < 6; i++) {
                expect(core.errors.DatabaseException.class, () -> resilient.authenticate(new LoginRequest("Alice", password)));
            }
            check(resilient.authenticate(new LoginRequest("Alice", password)) instanceof AuthenticationResult.Success,
                    "Database failures do not count toward the attempt limit");
            sessions.logout();

            LoginRequest invalid = new LoginRequest(" ", new char[0]);
            check(service.authenticate(invalid) instanceof AuthenticationResult.InvalidInput result && result.errors().size() == 2,
                    "Both required fields validated");
            expect(IllegalStateException.class, invalid::copyPassword);
            check(db.table("auth_sessions").count() == 0, "Validation creates no session");
            check(service.authenticate(new LoginRequest("Alice", "short".toCharArray())) instanceof AuthenticationResult.InvalidInput result
                    && result.errors().containsKey("password"), "Short password rejected by login validation");
            check(service.authenticate(new LoginRequest("Al", password)) instanceof AuthenticationResult.InvalidInput result
                    && result.errors().containsKey("login"), "Short login rejected by login validation");
            check(service.authenticate(new LoginRequest("Alice", "wrong-password-long".toCharArray())) instanceof AuthenticationResult.InvalidCredentials,
                    "Wrong password rejected");
            check(service.authenticate(new LoginRequest("missing", password)) instanceof AuthenticationResult.InvalidCredentials,
                    "Unknown user has same result");
            check(service.authenticate(new LoginRequest("' OR 1=1 --", password)) instanceof AuthenticationResult.InvalidInput,
                    "Unsupported login characters rejected before lookup");
            check(service.authenticate(new LoginRequest(" alice ", password)) instanceof AuthenticationResult.Success success
                    && success.user().id() == id, "Trimmed login and SQLite case insensitive lookup");
            check(sessions.current().orElseThrow().id() == id, "Current user set");
            check(secrets.bytes.length == 32, "Token has 256 random bits");
            String dbToken = (String) db.table("auth_sessions").first().orElseThrow().get("token_hash");
            check(!dbToken.equals(HexFormat.of().formatHex(secrets.bytes)), "Database stores token hash");
            var restarted = session(repository, db, secrets, clock);
            check(restarted.restore().orElseThrow().id() == id, "New service restores persisted session");
            byte[] firstToken = secrets.bytes.clone();
            restarted.start(repository.findById(id).orElseThrow());
            check(!Arrays.equals(firstToken, secrets.bytes), "Repeated login rotates token");
            check(db.table("auth_sessions").count() == 1, "Old active token revoked");
            byte[] revokedToken = secrets.bytes.clone();
            restarted.logout();
            check(restarted.current().isEmpty() && secrets.bytes == null && db.table("auth_sessions").count() == 0, "Logout clears all stores");
            secrets.bytes = revokedToken;
            check(session(repository, db, secrets, clock).restore().isEmpty(), "Copied revoked token cannot restore");

            sessions.start(repository.findById(id).orElseThrow());
            clock.now = clock.now.plus(Duration.ofDays(30));
            check(sessions.current().isEmpty(), "In-memory session expires");
            check(session(repository, db, secrets, clock).restore().isEmpty(), "Expired persisted session rejected");
            check(secrets.bytes == null && db.table("auth_sessions").count() == 0, "Expired presented token cleaned up");

            sessions.start(repository.findById(id).orElseThrow());
            db.table("users").where("id", id).update(Map.of("password_hash", passwords.hash("changed".toCharArray())));
            check(session(repository, db, secrets, clock).restore().isEmpty(), "Password change invalidates restore");
            sessions.start(repository.findById(id).orElseThrow());
            db.table("users").where("id", id).delete();
            check(db.table("auth_sessions").count() == 0, "User deletion cascades to sessions");
            check(session(repository, db, secrets, clock).restore().isEmpty(), "Deleted user cannot restore");
            secrets.bytes = new byte[10];
            check(session(repository, db, secrets, clock).restore().isEmpty() && secrets.bytes == null, "Malformed token removed");

            id = db.table("users").insert(Map.of("login", "Bob", "email", "bob@example.test", "password_hash", encoded));
            User bob = repository.findById(id).orElseThrow();
            secrets.failWrite = true;
            expect(IOException.class, () -> service.authenticate(new LoginRequest("Bob", password)));
            check(db.table("auth_sessions").count() == 0, "Authentication fails if session cannot be persisted");
            var failed = session(repository, db, secrets, clock);
            expect(IOException.class, () -> failed.start(bob));
            check(failed.current().isEmpty() && db.table("auth_sessions").count() == 0, "Write failure rolls back session creation");
            secrets.failWrite = false;
            failed.start(bob);
            secrets.failDelete = true;
            expect(IOException.class, failed::logout);
            check(failed.current().isEmpty() && db.table("auth_sessions").count() == 0, "Failed file deletion still revokes database token");
            secrets.failDelete = false;
            check(session(repository, db, secrets, clock).restore().isEmpty(), "Leftover file cannot restore logged-out user");

            Path protectedFile = directory.resolve("protected-token.bin");
            WindowsSecretStore windows = new WindowsSecretStore(protectedFile);
            byte[] largeSecret = new byte[4096];
            new java.security.SecureRandom().nextBytes(largeSecret);
            windows.write(largeSecret);
            check(Arrays.equals(windows.read().orElseThrow(), largeSecret), "DPAPI maximum size does not block stdout pipe");
            expect(IOException.class, () -> windows.write(new byte[4097]));
            byte[] token = new byte[32];
            new java.security.SecureRandom().nextBytes(token);
            windows.write(token);
            check(!Arrays.equals(Files.readAllBytes(protectedFile), token), "DPAPI file is not plaintext");
            check(Arrays.equals(new WindowsSecretStore(protectedFile).read().orElseThrow(), token), "DPAPI roundtrip from new instance");
            token[0] ^= 1;
            windows.write(token);
            check(Arrays.equals(windows.read().orElseThrow(), token), "Atomic replacement works on Windows");
            Files.write(protectedFile, new byte[] {1, 2, 3});
            expect(IOException.class, windows::read);
            windows.delete();
            check(windows.read().isEmpty(), "Protected secret deleted");
            System.out.println("Authentication checks passed: " + checks);
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) { Files.deleteIfExists(path); }
            }
            Files.delete(directory);
        }
    }

    private static PersistentSessionService session(AuthenticationRepository repository, Database db, SecretStore secrets, Clock clock) {
        return new PersistentSessionService(repository, new DatabaseSessionRepository(db), secrets, clock, Duration.ofDays(30));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) { throw new AssertionError(message); }
    }

    private static void expect(Class<? extends Throwable> type, Action work) throws Exception {
        try { work.run(); } catch (Throwable error) {
            check(type.isInstance(error), "Expected " + type.getSimpleName() + ", got " + error.getClass().getSimpleName());
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static final class MemorySecrets implements SecretStore {
        byte[] bytes;
        boolean failWrite;
        boolean failDelete;
        public Optional<byte[]> read() { return Optional.ofNullable(bytes).map(byte[]::clone); }
        public void write(byte[] value) throws IOException {
            if (failWrite) { throw new IOException("Test write failure"); }
            bytes = value.clone();
        }
        public void delete() throws IOException {
            if (failDelete) { throw new IOException("Test delete failure"); }
            bytes = null;
        }
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-25T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
