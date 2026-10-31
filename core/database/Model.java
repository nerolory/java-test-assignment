package core.database;

import core.errors.OptimisticLockException;

import core.errors.DatabaseException;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.LinkedHashMap;

/**
 * Базовый класс моделей: чтение, сохранение и удаление записи.
 *
 * <p>Модель не предназначена для одновременного использования несколькими потоками.
 * Таблица содержит генерируемый целочисленный id и целочисленную version.
 */
public abstract class Model {
    /**
     * Объект БД для чтения и сохранения модели.
     */
    protected final Database database;

    /**
     * Идентификатор записи. До сохранения — null.
     */
    protected Long id;

    /** Версия записи при последнем чтении. */
    private long version;

    /**
     * Сохраняет объект БД для операций модели.
     *
     * @param database объект БД для выполнения операций
     */
    protected Model(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    /**
     * Получает имя таблицы из имени класса: User → users, UserRole → user_roles.
     *
     * <p>Для другого имени таблицы метод нужно переопределить.
     *
     * @return имя таблицы без SQL-кавычек
     */
    protected String tableName() {
        return getClass().getSimpleName()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT) + "s";
    }

    /**
     * Возвращает поля модели, которые нужно записать в таблицу.
     *
     * @return непустая карта записываемых полей без id и version
     */
    protected abstract Map<String, Object> attributes();

    /**
     * Сохраняет поля для восстановления при откате. Модель с полями только для чтения
     * добавляет их здесь; обычная выборка не хранит копию строки рядом с объектом.
     * @return поля, принимаемые readAttributes
     */
    protected Map<String, Object> snapshotAttributes() {
        return attributes();
    }

    /**
     * Заполняет поля модели данными записи.
     *
     * @param row полная строка; до первой вставки значения, создаваемые БД, могут отсутствовать
     */
    protected abstract void readAttributes(Map<String, Object> row);

    /**
     * Создаёт запрос, возвращающий объекты указанной модели.
     *
     * @param <T> тип результата
     * @param database объект БД для выполнения операций
     * @param factory функция создания модели с указанной БД
     * @return запрос для указанной модели
     */
    protected static <T extends Model> ModelQuery<T> newQuery(
            Database database,
            Function<Database, T> factory
    ) {
        T prototype = factory.apply(database);

        // По образцу определяем таблицу. Для каждой строки создаём отдельную модель.
        return new ModelQuery<>(database.table(prototype.tableName()), row -> {
            T model = factory.apply(database);
            model.hydrate(row);
            return model;
        });
    }

    /**
     * Возвращает идентификатор без обращения к БД.
     *
     * @return первичный ключ или null для несохранённой модели
     */
    public Long getId() {
        return id;
    }

    /**
     * Возвращает версию последнего чтения.
     *
     * @return версия последнего чтения; до вставки — 0
     */
    public long getVersion() {
        return version;
    }

    /**
     * Заполняет модель и общие поля хранения.
     * @param row полная строка БД с id и version
     */
    final void hydrate(Map<String, Object> row) {
        readAttributes(row);
        id = ((Number) row.get("id")).longValue();
        version = ((Number) row.get("version")).longValue();
    }

    /**
     * Сохраняет поля перед составной операцией.
     *
     * @return состояние модели перед составной операцией
     */
    private Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>(snapshotAttributes());
        state.put("id", id);
        state.put("version", version);
        return state;
    }

    /**
     * Регистрирует восстановление состояния при откате транзакции.
     * @param tx активная сессия
     */
    private void restoreOnRollback(Database tx) {
        Map<String, Object> state = snapshot();
        tx.onRollback(() -> {
            try {
                readAttributes(state);
            } finally {
                id = (Long) state.get("id");
                version = ((Number) state.get("version")).longValue();
            }
        });
    }

    /**
     * Сохраняет запись и перечитывает её из БД.
     *
     * <p>Запись и чтение выполняются в одной транзакции. Обновление проверяет version.
     *
     * @throws DatabaseException при ошибке записи или перечитывания
     * @throws OptimisticLockException если запись изменена или удалена другим клиентом
     */
    public void save() {
        database.atomic(tx -> {
            restoreOnRollback(tx);
            Map<String, Object> values = new LinkedHashMap<>(attributes());
            if (values.isEmpty() || values.containsKey("id") || values.containsKey("version")) {
                throw new IllegalStateException("Model attributes must exclude id and version and cannot be empty");
            }
            long key;
            if (id == null) {
                values.put("version", 0L);
                key = tx.table(tableName()).insert(values);
            } else {
                values.put("version", Math.incrementExact(version));
                if (tx.table(tableName()).where("id", id).where("version", version).update(values) != 1) {
                    throw new OptimisticLockException();
                }
                key = id;
            }
            hydrate(tx.table(tableName()).where("id", key).first()
                    .orElseThrow(OptimisticLockException::new));
            return null;
        });
    }

    /**
     * Обновляет поля модели данными из БД.
     *
     * @throws DatabaseException при ошибке SELECT
     * @throws IllegalStateException если модель не сохранена или строка отсутствует
     */
    public void refresh() {
        if (id == null) {
            throw new IllegalStateException("Cannot refresh an unsaved model");
        }

        hydrate(database.table(tableName())
                .where("id", id)
                .first()
                .orElseThrow(() -> new IllegalStateException("Model row no longer exists")));
    }

    /**
     * Удаляет строку по id и сбрасывает id после успеха. Остальные поля объекта сохраняются.
     *
     * @throws DatabaseException при ошибке DELETE
     * @throws IllegalStateException если модель не сохранена или строка отсутствует
     */
    public void delete() {
        if (id == null) {
            throw new IllegalStateException("Cannot delete an unsaved model");
        }

        database.atomic(tx -> {
            restoreOnRollback(tx);
            if (tx.table(tableName()).where("id", id).where("version", version).delete() != 1) {
                throw new OptimisticLockException();
            }
            id = null;
            version = 0;
            return null;
        });
    }
}
