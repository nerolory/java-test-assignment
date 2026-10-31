package core.errors;

/** Запись изменена или удалена после загрузки модели. */
public final class OptimisticLockException extends IllegalStateException {
    /** Создаёт ошибку конфликта версий. */
    public OptimisticLockException() {
        super("Record was changed or deleted; reload before saving");
    }
}
