package tests.core;

import core.container.ServiceContainer;

/** Проверки контейнера без JavaFX и классов приложения. */
public final class ServiceContainerTest {
    public static void main(String[] args) {
        ServiceContainer services = new ServiceContainer();
        Object shared = new Object();
        services.register(container -> {
            container.instance(Object.class, shared);
            container.bind(StringBuilder.class, StringBuilder::new);
            container.bind(Consumer.class, () -> new Consumer(container.resolve(Object.class)));
        });

        check(services.resolve(Object.class) == shared, "Shared instance must be retained");
        check(services.resolve(StringBuilder.class) != services.resolve(StringBuilder.class),
                "Factory must create a fresh object");
        check(services.resolve(Consumer.class).dependency() == shared, "Dependency must be resolved by contract");
        expect(IllegalArgumentException.class, () -> services.resolve(Runnable.class));
        expect(IllegalStateException.class, () -> services.bind(Object.class, Object::new));
        check(services.resolve(Object.class) == shared, "Duplicate binding must not replace the original");

        services.bind(Runnable.class, () -> services.resolve(Runnable.class));
        expect(IllegalStateException.class, () -> services.resolve(Runnable.class));
        services.bind(CharSequence.class, () -> services.resolve(String.class));
        services.bind(String.class, () -> services.resolve(CharSequence.class).toString());
        expect(IllegalStateException.class, () -> services.resolve(String.class));
        check(services.resolve(Consumer.class).dependency() == shared, "Failed resolution must clear its chain");

        services.bind(AutoCloseable.class, () -> null);
        expect(NullPointerException.class, () -> services.resolve(AutoCloseable.class));
        System.out.println("Service container checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Runnable work) {
        try {
            work.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private record Consumer(Object dependency) { }
}
