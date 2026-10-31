package app.repositories;

import app.contracts.SessionRepository;
import core.database.Database;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Чтение, создание и отзыв сохранённых сессий. */
public final class DatabaseSessionRepository implements SessionRepository {
    private final Database database;

    public DatabaseSessionRepository(Database database) {
        this.database = database;
    }

    @Override
    public void createSession(String tokenHash, long userId, String fingerprint, Instant expiresAt) {
        database.table("auth_sessions").insert(Map.of("token_hash", tokenHash, "user_id", userId,
                "credential_fingerprint", fingerprint, "expires_at", expiresAt.toEpochMilli()));
    }

    @Override
    public Optional<StoredSession> findSession(String tokenHash) {
        return database.table("auth_sessions").where("token_hash", tokenHash).first().map(row ->
                new StoredSession(((Number) row.get("user_id")).longValue(),
                        (String) row.get("credential_fingerprint"),
                        Instant.ofEpochMilli(((Number) row.get("expires_at")).longValue())));
    }

    @Override
    public void deleteSession(String tokenHash) {
        database.table("auth_sessions").where("token_hash", tokenHash).delete();
    }
}
