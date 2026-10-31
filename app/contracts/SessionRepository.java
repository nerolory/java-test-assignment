package app.contracts;

import java.time.Instant;
import java.util.Optional;

/** Хранение токенов сессий. Запросы пользователей принадлежат отдельному контракту. */
public interface SessionRepository {
    void createSession(String tokenHash, long userId, String fingerprint, Instant expiresAt);

    Optional<StoredSession> findSession(String tokenHash);

    void deleteSession(String tokenHash);

    /** Запись сессии без исходного токена. */
    record StoredSession(long userId, String credentialFingerprint, Instant expiresAt) { }
}
