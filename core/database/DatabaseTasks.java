package core.database;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Ограниченная очередь фоновых операций БД. Результат UI обрабатывает в своём потоке. */
public final class DatabaseTasks implements AutoCloseable {
    /** База фоновых операций. */
    private final Database database;
    /** Исполнитель с фиксированным числом потоков. */
    private final ThreadPoolExecutor executor;
    /** Задачи, которые нужно отменить при закрытии. */
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();

    /**
     * Создаёт исполнитель с ограниченной очередью.
     *
     * @param database основной объект БД
     */
    public DatabaseTasks(Database database) {
        database.checkAsyncBoundary();
        this.database = database;
        var options = database.options();
        executor = new ThreadPoolExecutor(options.maxConnections(), options.maxConnections(),
                0, TimeUnit.MILLISECONDS,
                options.maxWaiting() == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(options.maxWaiting()),
                runnable -> {
                    Thread thread = new Thread(runnable, "database-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Ставит сценарий в очередь. Переполнение вызывает RejectedExecutionException.
     * Транзакцию нужно открывать внутри work; контекст вызывающего потока не переносится.
     * @param <T> тип результата
     * @param work сценарий работы с БД
     * @return задача; cancel(true) запрашивает отмену SQL и прерывает ожидание
     */
    public <T> Future<T> submit(Supplier<T> work) {
        database.checkAsyncBoundary();
        CancellationToken token = new CancellationToken();
        FutureTask<T> task = new FutureTask<>(() -> database.withCancellation(token, work)) {
            @Override
            public boolean cancel(boolean interrupt) {
                token.cancel();
                return super.cancel(interrupt);
            }

            @Override
            protected void done() {
                tasks.remove(this);
                executor.remove(this);
            }
        };
        tasks.add(task);
        try {
            executor.execute(task);
        } catch (RuntimeException error) {
            task.cancel(false);
            throw error;
        }
        return task;
    }

    /** Запрашивает отмену всех задач и прекращает приём новых. */
    @Override
    public void close() {
        executor.shutdown();
        tasks.forEach(task -> task.cancel(true));
        executor.shutdownNow();
    }
}
