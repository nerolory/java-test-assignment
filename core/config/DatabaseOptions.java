package core.config;

/**
 * Ограничения нагрузки одного экземпляра приложения.
 *
 * @param maxConnections число одновременных операций с соединениями
 * @param maxWaiting число ожидающих операций
 * @param acquireTimeoutMs максимальное ожидание свободного места
 * @param queryTimeoutMs время выполнения одного SQL-запроса
 * @param defaultLimit защитный лимит выборки без явного limit
 * @param maxLimit максимальный размер страницы
 * @param maxBatch максимальное число записей в пакете
 */
public record DatabaseOptions(int maxConnections, int maxWaiting, int acquireTimeoutMs,
                              int queryTimeoutMs, int defaultLimit, int maxLimit, int maxBatch) {
    /** Проверяет положительные ограничения и размер очереди. */
    public DatabaseOptions {
        if (maxConnections < 1 || maxWaiting < 0 || acquireTimeoutMs < 1 || queryTimeoutMs < 1
                || defaultLimit < 1 || maxLimit < defaultLimit || maxLimit == Integer.MAX_VALUE
                || maxBatch < 1 || (long) maxConnections + maxWaiting > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid database limits");
        }
    }

    /**
     * Создаёт ограничения для локального приложения.
     *
     * @return ограничения для локального приложения
     */
    public static DatabaseOptions defaults() {
        return new DatabaseOptions(4, 32, 2000, 5000, 100, 1000, 500);
    }
}
