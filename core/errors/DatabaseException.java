package core.errors;

/**
 * Ошибка инфраструктуры БД с сохранённой JDBC-причиной.
 */
public class DatabaseException extends RuntimeException {
    /**
     * Создаёт ошибку операции с сохранением первоначальной причины.
     *
     * @param message описание ошибки без чувствительных данных
     * @param cause исходная причина сбоя
     */
    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
