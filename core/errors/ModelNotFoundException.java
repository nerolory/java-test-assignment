package core.errors;

/**
 * Ошибка отсутствия обязательной модели, возвращаемая методом firstOrFail.
 */
public final class ModelNotFoundException extends RuntimeException {
    /**
     * Создаёт исключение отсутствия модели без раскрытия параметров запроса.
     */
    public ModelNotFoundException() {
        super("No model matches the query");
    }
}
