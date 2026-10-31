package core.container;

import core.contracts.ServiceProvider;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Регистрация и создание объектов. Настройка выполняется до запуска рабочих потоков. */
public final class ServiceContainer {
    /** Функции создания объектов по контракту или классу. */
    private final Map<Class<?>, Supplier<?>> bindings = new HashMap<>();

    /** Цепочка создания объектов в текущем потоке для обнаружения циклов. */
    private final ThreadLocal<Set<Class<?>>> resolving = ThreadLocal.withInitial(LinkedHashSet::new);

    /** Создаёт пустой контейнер. */
    public ServiceContainer() {
    }

    /**
     * Выполняет регистрацию провайдеров в указанном порядке.
     * @param providers провайдеры приложения
     */
    public void register(ServiceProvider... providers) {
        for (ServiceProvider provider : providers) {
            Objects.requireNonNull(provider).register(this);
        }
    }

    /**
     * Регистрирует фабрику, вызываемую при каждом resolve.
     * @param <T> тип объекта
     * @param type контракт или класс
     * @param factory функция создания объекта
     * @throws IllegalStateException если тип уже зарегистрирован
     */
    public <T> void bind(Class<T> type, Supplier<? extends T> factory) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(factory);

        if (bindings.putIfAbsent(type, factory) != null) {
            throw new IllegalStateException("Type is already registered: " + type.getName());
        }
    }

    /**
     * Регистрирует готовый общий объект. Его закрытием управляет приложение.
     * @param <T> тип объекта
     * @param type контракт или класс
     * @param instance общий объект
     */
    public <T> void instance(Class<T> type, T instance) {
        Objects.requireNonNull(type);
        T checked = type.cast(Objects.requireNonNull(instance));
        bind(type, () -> checked);
    }

    /**
     * Получает объект через зарегистрированную фабрику.
     * @param <T> тип результата
     * @param type контракт или класс
     * @return созданный или общий объект
     * @throws IllegalArgumentException если тип не зарегистрирован
     * @throws IllegalStateException если зависимости образуют цикл
     */
    public <T> T resolve(Class<T> type) {
        Objects.requireNonNull(type);
        Supplier<?> factory = bindings.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Type is not registered: " + type.getName());
        }

        Set<Class<?>> chain = resolving.get();
        if (!chain.add(type)) {
            throw new IllegalStateException("Circular dependency: " + type.getName());
        }

        try {
            return type.cast(Objects.requireNonNull(factory.get(), "Factory returned null: " + type.getName()));
        } finally {
            chain.remove(type);
            if (chain.isEmpty()) {
                resolving.remove();
            }
        }
    }
}
