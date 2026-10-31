package core.database;

/**
 * Активная транзакция текущего потока. Запрещает вложенные транзакции и смешивание
 * разных объектов БД в одной локальной транзакции. Глобальной БД здесь нет:
 * объект БД всегда передаётся явно.
 */
final class TransactionContext {
    /** Основной объект БД активной транзакции текущего потока. */
    private static final ThreadLocal<Database> active = new ThreadLocal<>();

    /**
     * Закрытый конструктор статического класса.
     */
    private TransactionContext() {
    }

    /**
     * Отклоняет смешивание нескольких БД в одной локальной транзакции.
     * @param requested основной объект вызываемой БД
     */
    static void checkDatabase(Database requested) {
        Database current = active.get();
        if (current != null && current != requested) {
            throw new IllegalStateException("Cannot mix database instances inside one transaction");
        }
    }

    /**
     * Отмечает начало транзакции в текущем потоке.
     * @param current основной объект новой транзакции
     */
    static void enter(Database current) {
        if (active.get() != null) {
            throw new IllegalStateException("Nested transactions are not supported");
        }
        active.set(current);
    }

    /** Снимает отметку после завершения транзакции. */
    static void leave() {
        active.remove();
    }
}
