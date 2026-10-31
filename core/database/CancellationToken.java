package core.database;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Сигнал отмены операции; доступен из нескольких потоков. */
public final class CancellationToken {
    /** Признак запрошенной отмены. */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Создаёт неотменённый сигнал. */
    public CancellationToken() { }

    /** Запрашивает отмену текущей и последующих операций этого сигнала. */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * Проверяет сигнал отмены и прерывание потока.
     *
     * @return true после cancel или прерывания текущего потока
     */
    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    /**
     * Прерывает отменённую операцию.
     *
     * @throws CancellationException если операция отменена
     */
    public void check() {
        if (isCancelled()) {
            throw new CancellationException("Database operation cancelled");
        }
    }
}
