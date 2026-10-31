package core.database;

import java.util.concurrent.atomic.LongAdder;

/** Счётчики запросов без хранения SQL, паролей и параметров. */
public final class DatabaseStatistics {
    /** Число выполненных SQL-операций. */
    private final LongAdder queries = new LongAdder();
    /** Число неуспешных операций. */
    private final LongAdder failures = new LongAdder();
    /** Суммарное время операций с ожиданием соединения. */
    private final LongAdder elapsedNanos = new LongAdder();

    /** Создаёт пустые счётчики. */
    DatabaseStatistics() { }

    /**
     * Учитывает завершённую SQL-операцию.
     *
     * @param nanos длительность
     * @param success успешность операции
     */
    void record(long nanos, boolean success) {
        queries.increment();
        elapsedNanos.add(nanos);
        if (!success) {
            failures.increment();
        }
    }

    /**
     * Читает текущие счётчики.
     *
     * @return приблизительный снимок конкурентно обновляемых счётчиков
     */
    public Snapshot snapshot() {
        return new Snapshot(queries.sum(), failures.sum(), elapsedNanos.sum());
    }

    /**
     * Состояние счётчиков; показатели читаются независимо друг от друга.
     * @param queries число SQL-операций
     * @param failures число ошибок
     * @param elapsedNanos суммарное время
     */
    public record Snapshot(long queries, long failures, long elapsedNanos) { }
}
