package tests.auth;

import core.errors.DatabaseException;

import app.contracts.AuthenticationService.AuthenticationResult;
import app.contracts.AuthenticationService.LoginRequest;
import app.validation.LoginValidator;
import app.contracts.RegistrationRepository;
import app.contracts.RegistrationService.*;
import app.validation.RegistrationValidator;
import app.errors.DuplicateAccountException;
import app.repositories.UserRepository;
import app.repositories.DatabaseSessionRepository;
import app.services.*;
import core.config.DatabaseConfig;
import core.contracts.PasswordHasher;
import core.contracts.SecretStore;
import core.database.*;
import core.database.drivers.SqliteDriver;
import core.security.Pbkdf2PasswordHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Валидация и регистрация в изолированной SQLite-базе, включая конкурентные запросы. */
public final class RegistrationTest {
    private static int checks;
    private static final String PASSWORD = " correct horse battery ";

    public static void main(String[] args) throws Exception {
        validateFields();
        Path directory = Files.createTempDirectory("registration-test-");
        try {
            Path file = Files.createFile(directory.resolve("test.db"));
            var config = new DatabaseConfig("sqlite", file, 5000);
            var connector = new DatabaseConnector(new SqliteDriver().createDataSource(config));
            String schema = Files.readString(Path.of("database/schema.sql"));
            connector.withConnection(connection -> {
                try (var statement = connection.createStatement()) { statement.executeUpdate(schema); }
                return null;
            });
            Database database = DatabaseConfigurator.configure(config);
            var repository = new UserRepository(database);
            var hasher = new TrackingHasher();
            var service = new DefaultRegistrationService(repository, hasher, new RegistrationValidator());

            RegistrationRequest empty = request("", "", "", "");
            check(service.register(empty) instanceof RegistrationResult.InvalidInput invalid && invalid.errors().size() == 4,
                    "All required fields returned together");
            check(hasher.calls == 0 && database.table("users").count() == 0, "Invalid data is not hashed or saved");
            expect(IllegalStateException.class, empty::copyPassword);
            expect(IllegalStateException.class, empty::copyConfirmation);

            RegistrationRequest request = request("  Alice  ", " Alice@Example.TEST ", PASSWORD, PASSWORD);
            RegistrationResult result = service.register(request);
            check(result instanceof RegistrationResult.Success, "Registration succeeds");
            long id = ((RegistrationResult.Success) result).userId();
            check(id > 0, "Generated ID returned");
            var row = database.table("users").where("id", id).first().orElseThrow();
            check(row.get("login").equals("Alice") && row.get("email").equals("alice@example.test"), "Identifiers normalized");
            String hash = (String) row.get("password_hash");
            check(!hash.equals(PASSWORD) && hasher.delegate.verify(PASSWORD.toCharArray(), hash), "Stored password is a compatible hash");
            check(row.get("created_at") != null && row.get("updated_at") != null && ((Number) row.get("version")).longValue() == 0,
                    "Database supplies timestamps and version");
            check(!row.containsKey("password") && !row.containsKey("password_confirmation"), "No plaintext or confirmation columns");
            check(database.table("auth_sessions").count() == 0, "Registration does not silently log in");
            check(allZero(hasher.lastPassword), "Hasher input cleared after success");
            expect(IllegalStateException.class, request::copyPassword);

            int calls = hasher.calls;
            check(errors(service.register(request("alice", "other@example.test", PASSWORD, PASSWORD))).keySet().equals(Set.of("login")),
                    "Duplicate login ignores ASCII case");
            check(errors(service.register(request("Other", "ALICE@EXAMPLE.TEST", PASSWORD, PASSWORD))).keySet().equals(Set.of("email")),
                    "Duplicate email ignores case");
            check(errors(service.register(request("ALICE", "alice@example.test", PASSWORD, PASSWORD))).size() == 2,
                    "Both duplicate fields reported");
            check(hasher.calls == calls && database.table("users").count() == 1, "Known duplicates avoid hashing and writes");
            expect(DuplicateAccountException.class, () -> repository.create("aLiCe", "different@example.test", hash));
            expect(DuplicateAccountException.class, () -> repository.create("Different", "ALICE@example.test", hash));
            expect(DatabaseException.class, () -> repository.create("Broken", "broken@example.test", ""));
            check(database.table("users").count() == 1, "Non-unique constraint errors remain infrastructure errors");

            // Проверяем полный путь: новая запись читается существующим сервисом входа.
            var authRepository = new UserRepository(database);
            var session = new PersistentSessionService(authRepository, new DatabaseSessionRepository(database), new MemorySecrets(), Clock.systemUTC(), Duration.ofDays(30));
            var authentication = new DefaultAuthenticationService(authRepository, hasher.delegate, session,
                    new LoginValidator(), new LoginAttemptLimiter(Clock.systemUTC()));
            check(authentication.authenticate(new LoginRequest("alice", PASSWORD.toCharArray())) instanceof AuthenticationResult.Success success
                    && success.user().id() == id, "New account authenticates through existing service");
            session.logout();

            race(database, repository, hash, "Concurrent", "CONCURRENT", "first@example.test", "second@example.test", "login");
            race(database, repository, hash, "EmailOne", "EmailTwo", "race@example.test", "RACE@example.test", "email");

            RegistrationRepository brokenRepository = new RegistrationRepository() {
                public Set<String> findConflicts(String login, String email) { return Set.of(); }
                public long create(String login, String email, String passwordHash) {
                    throw new DatabaseException("Test failure", null);
                }
            };
            var failureService = new DefaultRegistrationService(brokenRepository, hasher, new RegistrationValidator());
            RegistrationRequest failed = request("Failure", "failure@example.test", PASSWORD, PASSWORD);
            expect(DatabaseException.class, () -> failureService.register(failed));
            expect(IllegalStateException.class, failed::copyConfirmation);
            check(allZero(hasher.lastPassword), "Password cleared after database failure");

            RegistrationRequest cancelled = request("Cancelled", "cancelled@example.test", PASSWORD, PASSWORD);
            try {
                Thread.currentThread().interrupt();
                expect(CancellationException.class, () -> service.register(cancelled));
            } finally {
                Thread.interrupted();
            }
            check(!database.table("users").where("login", "Cancelled").exists(), "Cancelled operation does not insert");
            expect(IllegalStateException.class, cancelled::copyPassword);
            System.out.println("Registration checks passed: " + checks);
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) { Files.deleteIfExists(path); }
            }
            Files.delete(directory);
        }
    }

    private static void validateFields() {
        var validator = new RegistrationValidator();
        for (String login : List.of("ab", "a b", "юзер", "x".repeat(129), "name' OR 1=1")) {
            check(validate(validator, login, "a@example.test", PASSWORD, PASSWORD).containsKey("login"), "Invalid login: " + login.length());
        }
        for (String login : List.of("abc", "user.name_1-test", "x".repeat(128))) {
            check(validate(validator, login, "a@example.test", PASSWORD, PASSWORD).isEmpty(), "Valid login boundary");
        }
        for (String email : List.of("name", "@example.test", "a@@example.test", "a@", "a@localhost", "a..b@example.test",
                ".a@example.test", "a.@example.test", "a@-example.test", "a@example-.test", "a@example..test", "a@example.test.",
                "a@" + "x".repeat(64) + ".test", "x".repeat(65) + "@example.test", "a b@example.test",
                "a@" + ("x".repeat(63) + ".").repeat(4) + "test", "a@пример.рф")) {
            check(validate(validator, "valid", email, PASSWORD, PASSWORD).containsKey("email"), "Invalid email rejected");
        }
        for (String email : List.of("name+tag@example.test", "o'connor@example.test", "a@xn--e1afmkfd.xn--p1ai",
                "x".repeat(64) + "@example.test", "x".repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(61))) {
            check(validate(validator, "valid", email, PASSWORD, PASSWORD).isEmpty(), "Supported email accepted");
        }
        for (String password : List.of("x".repeat(14), "x".repeat(129), " ".repeat(15))) {
            check(validate(validator, "valid", "a@example.test", password, password).containsKey("password"), "Invalid password rejected");
        }
        for (String password : List.of("x".repeat(15), "x".repeat(128), "🙂".repeat(15), PASSWORD)) {
            check(validate(validator, "valid", "a@example.test", password, password).isEmpty(), "Password codepoint boundary and spaces supported");
        }
        check(validate(validator, "valid", "a@example.test", "🙂".repeat(14), "🙂".repeat(14)).containsKey("password"),
                "Unicode surrogate pairs count once");
        check(validate(validator, "valid", "a@example.test", PASSWORD, PASSWORD.strip()).containsKey("passwordConfirmation"),
                "Confirmation must match spaces exactly");
        char[] original = PASSWORD.toCharArray();
        RegistrationRequest request = new RegistrationRequest("valid", "a@example.test", original, original);
        original[0] = 'x';
        check(!Arrays.equals(original, request.copyPassword()), "Request copies password arrays");
        request.close();
        check(original[0] == 'x', "Closing request does not modify caller arrays");
    }

    private static void race(Database db, RegistrationRepository delegate, String hash,
            String loginOne, String loginTwo, String emailOne, String emailTwo, String field) throws Exception {
        CyclicBarrier checked = new CyclicBarrier(2);
        RegistrationRepository racing = new RegistrationRepository() {
            public Set<String> findConflicts(String login, String email) {
                Set<String> conflicts = delegate.findConflicts(login, email);
                if (!conflicts.isEmpty()) { throw new AssertionError("Race must begin with free identifiers"); }
                try { checked.await(10, TimeUnit.SECONDS); }
                catch (Exception error) { throw new IllegalStateException(error); }
                return conflicts;
            }
            public long create(String login, String email, String passwordHash) { return delegate.create(login, email, passwordHash); }
        };
        PasswordHasher precomputed = new PasswordHasher() {
            public String hash(char[] password) { return hash; }
            public boolean verify(char[] password, String encoded) { throw new UnsupportedOperationException(); }
        };
        var service = new DefaultRegistrationService(racing, precomputed, new RegistrationValidator());
        long before = db.table("users").count();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> service.register(request(loginOne, emailOne, PASSWORD, PASSWORD)));
            var two = pool.submit(() -> service.register(request(loginTwo, emailTwo, PASSWORD, PASSWORD)));
            List<RegistrationResult> results = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            check(results.stream().filter(RegistrationResult.Success.class::isInstance).count() == 1, "Only one concurrent registration succeeds");
            check(results.stream().filter(RegistrationResult.InvalidInput.class::isInstance)
                    .map(RegistrationResult.InvalidInput.class::cast).anyMatch(result -> result.errors().containsKey(field)),
                    "Losing request receives field conflict");
        }
        check(db.table("users").count() == before + 1, "Race inserts exactly one user");
    }

    private static Map<String, String> validate(RegistrationValidator validator, String login, String email, String password, String confirmation) {
        var registration = validator.validate(login, email, password.toCharArray(), confirmation.toCharArray());
        var authentication = new LoginValidator().validate(login, password.toCharArray());
        check(Objects.equals(registration.get("login"), authentication.get("login"))
                && Objects.equals(registration.get("password"), authentication.get("password")),
                "Login and registration enforce identical credential rules");
        return registration;
    }

    private static RegistrationRequest request(String login, String email, String password, String confirmation) {
        return new RegistrationRequest(login, email, password.toCharArray(), confirmation.toCharArray());
    }

    private static Map<String, String> errors(RegistrationResult result) {
        if (!(result instanceof RegistrationResult.InvalidInput invalid)) { throw new AssertionError("Expected invalid input"); }
        return invalid.errors();
    }

    private static boolean allZero(char[] value) {
        for (char character : value) { if (character != '\0') { return false; } }
        return true;
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

    private static final class TrackingHasher implements PasswordHasher {
        final PasswordHasher delegate = new Pbkdf2PasswordHasher();
        int calls;
        char[] lastPassword;
        public String hash(char[] password) { calls++; lastPassword = password; return delegate.hash(password); }
        public boolean verify(char[] password, String encoded) { return delegate.verify(password, encoded); }
    }

    private static final class MemorySecrets implements SecretStore {
        byte[] value;
        public Optional<byte[]> read() { return Optional.ofNullable(value).map(byte[]::clone); }
        public void write(byte[] bytes) { value = bytes.clone(); }
        public void delete() { value = null; }
    }
}
