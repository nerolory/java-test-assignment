package app.models;

import core.database.Database;
import core.database.Model;
import core.database.ModelQuery;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Database mapping only. Registration and password hashing belong to a service. */
public final class User extends Model {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private String login;
    private String email;
    private String passwordHash;
    private Instant createdAt;
    private Instant updatedAt;

    /** База передаётся явно: у моделей нет глобального объекта БД. */
    public User(Database database) {
        super(database);
    }

    public static Optional<User> find(Database database, long id) {
        return on(database).where("id", id).first();
    }

    /** Запросы к пользователям через переданную БД; внутри её транзакции присоединяются к ней. */
    public static ModelQuery<User> on(Database database) {
        return newQuery(database, User::new);
    }

    @Override
    protected void readAttributes(Map<String, Object> row) {
        login = (String) row.get("login");
        email = (String) row.get("email");
        passwordHash = (String) row.get("password_hash");
        createdAt = timestamp((String) row.get("created_at"));
        updatedAt = timestamp((String) row.get("updated_at"));
    }

    @Override
    protected Map<String, Object> attributes() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("login", login);
        values.put("email", email);
        values.put("password_hash", passwordHash);
        return values;
    }

    @Override
    protected Map<String, Object> snapshotAttributes() {
        Map<String, Object> values = attributes();
        values.put("created_at", createdAt == null ? null : TIMESTAMP_FORMAT.format(createdAt.atOffset(ZoneOffset.UTC)));
        values.put("updated_at", updatedAt == null ? null : TIMESTAMP_FORMAT.format(updatedAt.atOffset(ZoneOffset.UTC)));
        return values;
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static Instant timestamp(String value) {
        if (value == null) {
            return null;
        }
        return LocalDateTime.parse(value, TIMESTAMP_FORMAT)
                .toInstant(ZoneOffset.UTC);
    }

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getEmail() { return email; }
    /** @return сохранённый хеш для сервисов проверки пароля и сессии */
    public String getPasswordHash() { return passwordHash; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
