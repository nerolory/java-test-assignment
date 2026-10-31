package core.errors;

/** Выборка превышает защитный лимит и требует явной пагинации. */
public final class ResultLimitException extends IllegalStateException {
    /**
     * Создаёт ошибку превышения лимита выборки.
     *
     * @param limit превышенный лимит
     */
    public ResultLimitException(int limit) {
        super("Result exceeds " + limit + " rows; use limit(), page() or chunkById()");
    }
}
