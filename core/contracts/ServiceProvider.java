package core.contracts;

import core.container.ServiceContainer;

/** Регистрация зависимостей отдельной части приложения. */
@FunctionalInterface
public interface ServiceProvider {
    /**
     * Добавляет привязки контрактов и функции создания объектов.
     * @param services контейнер приложения
     */
    void register(ServiceContainer services);
}
