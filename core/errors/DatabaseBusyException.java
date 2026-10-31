package core.errors;

/** Очередь операций заполнена или время ожидания соединения истекло. */
public final class DatabaseBusyException extends DatabaseException {
    /**
     * Создаёт ошибку ограничения нагрузки.
     *
     * @param message причина отказа
     */
    public DatabaseBusyException(String message) {
        super(message, null);
    }
}
