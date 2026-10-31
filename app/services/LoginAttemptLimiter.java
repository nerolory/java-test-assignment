package app.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Лимит попыток входа в одном процессе: 5 на логин и 30 суммарно за минуту. */
public final class LoginAttemptLimiter {
    private static final int ACCOUNT_LIMIT = 5;
    private static final int GLOBAL_LIMIT = 30;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final Clock clock;
    private final Map<String, Window> accounts = new HashMap<>();
    private Window global;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Учитывает попытку до проверки пароля, чтобы параллельные запросы не обходили лимит.
     * @param login нормализованный логин
     * @return время, после которого можно повторить вход, если лимит исчерпан
     */
    public synchronized Optional<Instant> reserve(String login) {
        Instant now = clock.instant();
        // Записи хранятся не дольше окна; их число ограничено общим лимитом.
        accounts.entrySet().removeIf(entry -> !entry.getValue().until().isAfter(now));
        String key = key(login);
        Window account = current(accounts.get(key), now);
        global = current(global, now);
        if (account.attempts() >= ACCOUNT_LIMIT) {
            return Optional.of(account.until());
        }
        if (global.attempts() >= GLOBAL_LIMIT) {
            return Optional.of(global.until());
        }
        accounts.put(key, account.next());
        global = global.next();
        return Optional.empty();
    }

    /**
     * Сбрасывает счётчик логина после верного пароля. Общий счётчик сохраняется.
     * @param login нормализованный логин
     */
    public synchronized void succeeded(String login) {
        accounts.remove(key(login));
    }

    /** SQLite сравнивает логины через NOCASE, поэтому регистр не должен давать новый счётчик. */
    private static String key(String login) {
        return login.toLowerCase(Locale.ROOT);
    }

    private static Window current(Window window, Instant now) {
        return window == null || !window.until().isAfter(now) ? new Window(0, now.plus(WINDOW)) : window;
    }

    private record Window(int attempts, Instant until) {
        Window next() {
            return new Window(attempts + 1, until);
        }
    }
}
