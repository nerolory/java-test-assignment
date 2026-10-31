package core.errors;

/** Нарушено ограничение уникальности записи. */
public final class UniqueConstraintException extends DatabaseException {
    /**
     * Сохраняет причину JDBC без привязки приложения к конкретному драйверу.
     * @param cause ошибка драйвера
     */
    public UniqueConstraintException(Throwable cause) {
        super("Unique constraint violated", cause);
    }
}
