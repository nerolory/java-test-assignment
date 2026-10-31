package core.database;

import core.errors.ModelNotFoundException;

import core.errors.DatabaseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Запрос, возвращающий объекты модели.
 *
 * <p>Выполняется при вызове get(), first() или firstOrFail().
 *
 * @param <T> тип модели
 */
public final class ModelQuery<T extends Model> {
    /**
     * SQL-запрос с заданными условиями.
     */
    private final QueryBuilder query;

    /**
     * Функция преобразования одной строки в модель.
     */
    private final Function<Map<String, Object>, T> hydrate;

    /**
     * Сохраняет запрос и функцию создания модели.
     *
     * @param query SQL-запрос
     * @param hydrate функция создания модели из строки
     */
    ModelQuery(QueryBuilder query, Function<Map<String, Object>, T> hydrate) {
        this.query = query;
        this.hydrate = hydrate;
    }

    /**
     * Добавляет условие равенства и возвращает новый запрос.
     *
     * <p>Для null используется IS NULL.
     *
     * @param column простое имя колонки
     * @param value значение параметра; null означает IS NULL
     * @return запрос с добавленным условием
     * @throws IllegalArgumentException если имя колонки недопустимо
     */
    public ModelQuery<T> where(String column, Object value) {
        return new ModelQuery<>(query.where(column, value), hydrate);
    }

    /**
     * Возвращает список найденных моделей.
     *
     * @return неизменяемый список моделей; пустой, если ничего не найдено
     * @throws DatabaseException при ошибке JDBC
     */
    public List<T> get() {
        return query.get(hydrate);
    }

    /**
     * Ограничивает число моделей.
     *
     * @param size число моделей
     * @return запрос с явным лимитом
     */
    public ModelQuery<T> limit(int size) {
        return new ModelQuery<>(query.limit(size), hydrate);
    }

    /**
     * Выбирает страницу моделей.
     *
     * @param number номер страницы с 1
     * @param size размер страницы
     * @return запрос страницы
     */
    public ModelQuery<T> page(int number, int size) {
        return new ModelQuery<>(query.page(number, size), hydrate);
    }

    /**
     * Задаёт устойчивую сортировку.
     *
     * @param column колонка сортировки
     * @return запрос по возрастанию колонки и id
     */
    public ModelQuery<T> orderBy(String column) {
        return orderBy(column, false);
    }

    /**
     * Задаёт устойчивую сортировку.
     *
     * @param column колонка сортировки
     * @param descending обратный порядок
     * @return запрос с сортировкой
     */
    public ModelQuery<T> orderBy(String column, boolean descending) {
        return new ModelQuery<>(query.orderBy(column, descending), hydrate);
    }

    /**
     * Выбирает модели после указанного ключа.
     *
     * @param id последний обработанный ключ
     * @return запрос следующих моделей по возрастанию id
     */
    public ModelQuery<T> afterId(long id) {
        return new ModelQuery<>(query.afterId(id), hydrate);
    }

    /**
     * Считает совпадения без загрузки моделей.
     *
     * @return число совпадений без ограничений страницы
     */
    public long count() {
        return query.count();
    }

    /**
     * Проверяет наличие совпадений.
     *
     * @return наличие хотя бы одного совпадения
     */
    public boolean exists() {
        return query.exists();
    }

    /**
     * Обрабатывает модели порциями без накопления общего списка.
     * @param size размер порции
     * @param consumer обработчик порции; не должен изменять id записей
     */
    public void chunkById(int size, Consumer<List<T>> consumer) {
        query.chunks(size, hydrate, model -> model.getId(), consumer);
    }

    /**
     * Возвращает первую найденную модель.
     *
     * @return модель в Optional; пустой Optional при отсутствии совпадений
     * @throws DatabaseException при ошибке JDBC
     */
    public Optional<T> first() {
        return query.first().map(hydrate);
    }

    /**
     * Возвращает первую модель. Если записей нет, выбрасывает исключение.
     *
     * @return найденная модель
     * @throws ModelNotFoundException если совпадений нет
     * @throws DatabaseException при ошибке JDBC
     */
    public T firstOrFail() {
        return first().orElseThrow(ModelNotFoundException::new);
    }
}
