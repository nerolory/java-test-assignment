package app.services;

import app.contracts.SessionService.AuthenticatedUser;
import app.models.User;
import app.contracts.AuthenticationRepository;
import app.contracts.SessionRepository;
import app.contracts.SessionService;
import core.contracts.SecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

/** Сессия на 30 дней: в файле защищённый токен, в БД только его SHA-256. */
public final class PersistentSessionService implements SessionService {
    private final AuthenticationRepository users;
    private final SessionRepository repository;
    private final SecretStore secrets;
    private final Clock clock;
    private final Duration lifetime;
    private final SecureRandom random = new SecureRandom();
    private AuthenticatedUser user;
    private String tokenHash;
    private Instant expiresAt;

    public PersistentSessionService(AuthenticationRepository users, SessionRepository repository,
            SecretStore secrets, Clock clock, Duration lifetime) {
        if (lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("Session lifetime must be positive");
        }
        this.repository = repository;
        this.users = users;
        this.secrets = secrets;
        this.clock = clock;
        this.lifetime = lifetime;
    }

    @Override
    public synchronized void start(User verifiedUser) throws IOException {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String hash = digest(token);
        Instant expiration = clock.instant().plus(lifetime);
        try {
            // Повторный вход отзывает предыдущий токен этого экземпляра приложения.
            if (tokenHash != null) {
                repository.deleteSession(tokenHash);
                clearMemory();
            }
            repository.createSession(hash, verifiedUser.getId(), fingerprint(verifiedUser.getPasswordHash()), expiration);
            try {
                secrets.write(token);
            } catch (IOException | RuntimeException error) {
                try {
                    repository.deleteSession(hash);
                } catch (RuntimeException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
                throw error;
            }
            user = AuthenticatedUser.from(verifiedUser);
            tokenHash = hash;
            expiresAt = expiration;
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    @Override
    public synchronized Optional<AuthenticatedUser> restore() throws IOException {
        clearMemory();
        var saved = secrets.read();
        if (saved.isEmpty()) {
            return Optional.empty();
        }
        byte[] token = saved.get();
        try {
            if (token.length != 32) {
                secrets.delete();
                return Optional.empty();
            }
            String hash = digest(token);
            var stored = repository.findSession(hash);
            var candidate = stored.flatMap(session -> users.findById(session.userId()));
            if (stored.isEmpty() || candidate.isEmpty() || !stored.get().expiresAt().isAfter(clock.instant())
                    || !stored.get().credentialFingerprint().equals(fingerprint(candidate.get().getPasswordHash()))) {
                repository.deleteSession(hash);
                secrets.delete();
                return Optional.empty();
            }
            user = AuthenticatedUser.from(candidate.get());
            tokenHash = hash;
            expiresAt = stored.get().expiresAt();
            return Optional.of(user);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    @Override
    public synchronized Optional<AuthenticatedUser> current() {
        return user != null && expiresAt.isAfter(clock.instant()) ? Optional.of(user) : Optional.empty();
    }

    @Override
    public synchronized void logout() throws IOException {
        // Сначала отзываем токен в БД. Даже оставшийся файл не восстановит вход.
        if (tokenHash != null) {
            repository.deleteSession(tokenHash);
        }
        clearMemory();
        secrets.delete();
    }

    private void clearMemory() {
        user = null;
        tokenHash = null;
        expiresAt = null;
    }

    private static String fingerprint(String encodedPassword) {
        return digest(encodedPassword.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
