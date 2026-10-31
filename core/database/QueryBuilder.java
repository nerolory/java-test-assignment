package core.database;

import core.errors.ResultLimitException;

import core.errors.DatabaseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Построение и выполнение SQL-запросов к таблице.
 */
public final class QueryBuilder {
    /**
     * Объект БД для выполнения запросов.
     */
    private final Database database;

    /**
     * Проверенное имя таблицы в SQL-кавычках.
     */
    private final String table;

    /**
     * Условия WHERE в порядке добавления, без значений параметров.
     */
    private final List<String> predicates;

    /**
     * Значения параметров в порядке знаков вопроса в SQL.
     */
    private final List<Object> parameters;

    /**
     * Разрешение массового UPDATE/DELETE без WHERE.
     */
    private final boolean allowAll;

    /** Список выбранных колонок или звёздочка. */
    private final String columns;
    /** Порядок строк без ключевого слова ORDER BY. */
    private final String order;
    /** Явный лимит или null для защитного ограничения. */
    private final Integer limit;
    /** Смещение страницы. */
    private final long offset;

    /**
     * Создаёт объект запроса с указанными условиями.
     *
     * @param database объект БД для выполнения операций
     * @param table простое имя таблицы без SQL-кавычек
     * @throws IllegalArgumentException если имя таблицы недопустимо
     */
    QueryBuilder(Database database, String table) {
        this(database, identifier(database, table), List.of(), List.of(), false);
    }

    /**
     * Создаёт объект запроса с указанными условиями.
     *
     * @param database объект БД для выполнения операций
     * @param table уже проверенное имя таблицы в SQL-кавычках
     * @param predicates упорядоченные SQL-условия без пользовательских значений
     * @param parameters значения параметров в порядке их записи в SQL
     * @param allowAll явное разрешение массовой записи без WHERE
     */
    private QueryBuilder(
            Database database,
            String table,
            List<String> predicates,
            List<Object> parameters,
            boolean allowAll
    ) {
        this(database, table, predicates, parameters, allowAll, "*", "", null, 0);
    }

    /**
     * Создаёт неизменяемый запрос.
     * @param database объект БД
     * @param table имя таблицы в кавычках
     * @param predicates условия
     * @param parameters параметры
     * @param allowAll разрешение массовой записи
     * @param columns выбранные колонки
     * @param order сортировка
     * @param limit явный лимит
     * @param offset смещение
     */
    private QueryBuilder(Database database, String table, List<String> predicates, List<Object> parameters,
                         boolean allowAll, String columns, String order, Integer limit, long offset) {
        this.database = database;
        this.table = table;
        this.predicates = List.copyOf(predicates);
        // Список нельзя изменить, но null в нём допустим.
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.allowAll = allowAll;
        this.columns = columns;
        this.order = order;
        this.limit = limit;
        this.offset = offset;
    }

    /**
     * Добавляет условие равенства и возвращает новый запрос.
     *
     * <p>Для null используется IS NULL.
     *
     * @param column простое имя колонки
     * @param value значение параметра; null означает IS NULL
     * @return новый билдер с дополнительным условием
     * @throws IllegalArgumentException если имя колонки недопустимо
     */
    public QueryBuilder where(String column, Object value) {
        List<String> nextPredicates = new ArrayList<>(predicates);
        List<Object> nextParameters = new ArrayList<>(parameters);

        nextPredicates.add(identifier(column) + (value == null ? " IS NULL" : " = ?"));

        // У IS NULL нет параметра для подстановки.
        if (value != null) {
            nextParameters.add(value);
        }

        return new QueryBuilder(database, table, nextPredicates, nextParameters, allowAll, columns, order, limit, offset);
    }

    /**
     * Разрешает изменение или удаление всех строк. Заданные условия сохраняются.
     *
     * @return новый билдер с разрешением массовой записи
     */
    public QueryBuilder allRows() {
        return new QueryBuilder(database, table, predicates, parameters, true, columns, order, limit, offset);
    }

    /**
     * Возвращает строки. Без явного limit превышение defaultLimit вызывает ResultLimitException.
     *
     * @return результат выборки; список пуст при отсутствии совпадений
     * @throws DatabaseException при ошибке JDBC
     */
    public List<Map<String, Object>> get() {
        return get(Function.identity());
    }

    /**
     * Преобразует строки по мере чтения, без промежуточного списка карт.
     * @param <T> тип результата
     * @param mapper преобразование одной строки
     * @return ограниченный список
     */
    <T> List<T> get(Function<Map<String, Object>, T> mapper) {
        int size = limit == null ? database.options().defaultLimit() : limit;
        List<T> rows = database.select(selectSql(columns, limit == null ? size + 1 : size), parameters, mapper);
        if (limit == null && rows.size() > size) {
            throw new ResultLimitException(size);
        }
        return rows;
    }

    /**
     * Ограничивает число строк.
     *
     * @param size число строк от 1 до maxLimit
     * @return запрос с явным ограничением
     */
    public QueryBuilder limit(int size) {
        checkLimit(size);
        return new QueryBuilder(database, table, predicates, parameters, allowAll, columns, order, size, offset);
    }

    /**
     * Создаёт страницу с устойчивой сортировкой по id или выбранной колонке и id.
     * @param number номер страницы с 1
     * @param size размер страницы
     * @return запрос страницы
     */
    public QueryBuilder page(int number, int size) {
        checkLimit(size);
        if (number < 1) {
            throw new IllegalArgumentException("Page number must be positive");
        }
        String ordering = order.isEmpty() ? identifier("id") + " ASC" : order;
        return new QueryBuilder(database, table, predicates, parameters, allowAll, columns, ordering,
                size, (long) (number - 1) * size);
    }

    /**
     * Задаёт устойчивую сортировку.
     *
     * @param column колонка сортировки
     * @return сортировка по возрастанию, с id для одинаковых значений
     */
    public QueryBuilder orderBy(String column) {
        return orderBy(column, false);
    }

    /**
     * Задаёт устойчивую сортировку.
     *
     * @param column колонка сортировки
     * @param descending обратный порядок
     * @return запрос с устойчивой сортировкой; таблица должна содержать id
     */
    public QueryBuilder orderBy(String column, boolean descending) {
        if (predicates.contains(identifier("id") + " > ?") && (!"id".equals(column) || descending)) {
            throw new IllegalStateException("ID cursor requires ascending id order");
        }
        String ordering = identifier(column) + (descending ? " DESC" : " ASC");
        if (!"id".equals(column)) {
            ordering += ", " + identifier("id") + " ASC";
        }
        return new QueryBuilder(database, table, predicates, parameters, allowAll, columns, ordering, limit, offset);
    }

    /**
     * Выбирает строки после указанного целочисленного id. Глубокий OFFSET не используется.
     * @param id последний обработанный ключ
     * @return запрос по возрастанию id
     */
    public QueryBuilder afterId(long id) {
        requireIdOrder();
        List<String> conditions = new ArrayList<>(predicates);
        List<Object> arguments = new ArrayList<>(parameters);
        conditions.add(identifier("id") + " > ?");
        arguments.add(id);
        return new QueryBuilder(database, table, conditions, arguments, allowAll, columns,
                identifier("id") + " ASC", limit, 0);
    }

    /**
     * Выбирает колонки для проекции. ModelQuery всегда читает полную модель.
     * @param names простые имена колонок
     * @return запрос с указанными колонками
     */
    public QueryBuilder select(String... names) {
        if (names.length == 0) {
            throw new IllegalArgumentException("Select at least one column");
        }
        StringJoiner selected = new StringJoiner(", ");
        for (String name : names) {
            selected.add(identifier(name));
        }
        return new QueryBuilder(database, table, predicates, parameters, allowAll, selected.toString(), order, limit, offset);
    }

    /**
     * Считает все совпадения.
     *
     * @return число всех совпадений; limit, page и orderBy не учитываются
     */
    public long count() {
        return ((Number) database.select("SELECT COUNT(*) AS total FROM " + table + conditions(), parameters)
                .getFirst().get("total")).longValue();
    }

    /**
     * Проверяет наличие совпадений.
     *
     * @return наличие совпадений; limit, page и orderBy не учитываются
     */
    public boolean exists() {
        return !database.select("SELECT 1 AS found FROM " + table + conditions()
                + database.dialect().pagination(1, 0), parameters).isEmpty();
    }

    /**
     * Обрабатывает строки порциями по id. Не гарантирует снимок при конкурентных изменениях.
     * @param size размер порции
     * @param consumer обработчик после закрытия результата JDBC
     */
    public void chunkById(int size, Consumer<List<Map<String, Object>>> consumer) {
        chunks(size, Function.identity(), row -> ((Number) row.get("id")).longValue(), consumer);
    }

    /**
     * Читает полные записи порциями, сохраняя в памяти одну порцию.
     * @param <T> тип элемента
     * @param size размер порции
     * @param mapper преобразование строки
     * @param key чтение id элемента
     * @param consumer обработчик порции
     */
    <T> void chunks(int size, Function<Map<String, Object>, T> mapper,
                    java.util.function.ToLongFunction<T> key, Consumer<List<T>> consumer) {
        Objects.requireNonNull(consumer);
        checkLimit(size);
        requireIdOrder();
        if (limit != null || offset != 0 || !columns.equals("*")) {
            throw new IllegalStateException("chunkById cannot combine with limit, page or projection");
        }
        QueryBuilder next = orderBy("id").limit(size);
        while (true) {
            List<T> rows = next.get(mapper);
            if (rows.isEmpty()) {
                return;
            }
            long last = key.applyAsLong(rows.getLast());
            consumer.accept(rows);
            if (rows.size() < size) {
                return;
            }
            next = afterId(last).limit(size);
        }
    }

    /** Проверяет совместимость курсора с сортировкой. */
    private void requireIdOrder() {
        if ((!order.isEmpty() && !order.equals(identifier("id") + " ASC")) || offset != 0) {
            throw new IllegalStateException("ID cursor requires ascending id order without offset");
        }
    }

    /**
     * Проверяет размер страницы.
     *
     * @param size проверяемый размер страницы
     */
    private void checkLimit(int size) {
        if (size < 1 || size > database.options().maxLimit()) {
            throw new IllegalArgumentException("Page size must be between 1 and " + database.options().maxLimit());
        }
    }

    /**
     * Собирает ограниченный SELECT.
     *
     * @param selection SQL выбранных колонок
     * @param size ограничение строк
     * @return SELECT с условиями и ограничением
     */
    private String selectSql(String selection, int size) {
        return "SELECT " + selection + " FROM " + table + conditions()
                + (order.isEmpty() ? "" : " ORDER BY " + order)
                + database.dialect().pagination(size, offset);
    }

    /**
     * Выполняет SELECT с ограничением в одну строку.
     *
     * @return строка в Optional; пустой Optional при отсутствии совпадений
     * @throws DatabaseException при ошибке JDBC
     */
    public Optional<Map<String, Object>> first() {
        return database.select(selectSql(columns, 1), parameters)
                .stream()
                .findFirst();
    }

    /**
     * Вставляет одну строку и возвращает сгенерированный целочисленный ключ.
     *
     * @param values колонки и значения для записи
     * @return идентификатор добавленной записи
     * @throws IllegalStateException если для INSERT указаны условия WHERE
     * @throws IllegalArgumentException если значения пусты или имя колонки недопустимо
     * @throws DatabaseException при ошибке JDBC
     */
    public long insert(Map<String, Object> values) {
        requireWriteShape();
        if (!predicates.isEmpty()) {
            throw new IllegalStateException("INSERT does not accept WHERE");
        }

        if (values.isEmpty()) {
            throw new IllegalArgumentException("INSERT values are required");
        }

        var columns = new StringJoiner(", ");
        var placeholders = new StringJoiner(", ");
        List<Object> arguments = new ArrayList<>();

        // Порядок колонок должен совпадать с порядком значений.
        values.forEach((column, value) -> {
            columns.add(identifier(column));
            placeholders.add("?");
            arguments.add(value);
        });

        return database.insert(
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
                arguments
        );
    }

    /**
     * Обновляет указанные колонки. Без WHERE требуется явный вызов allRows.
     *
     * @param values колонки и значения для записи
     * @return количество изменённых строк
     * @throws IllegalStateException если нет WHERE и разрешения allRows
     * @throws IllegalArgumentException если значения пусты или имя колонки недопустимо
     * @throws DatabaseException при ошибке JDBC
     */
    public int update(Map<String, Object> values) {
        requireWriteShape();
        requireScope();

        if (values.isEmpty()) {
            throw new IllegalArgumentException("UPDATE values are required");
        }

        var assignments = new StringJoiner(", ");
        List<Object> arguments = new ArrayList<>();

        values.forEach((column, value) -> {
            assignments.add(identifier(column) + " = ?");
            arguments.add(value);
        });

        // Сначала подставляются значения SET, затем значения WHERE.
        arguments.addAll(parameters);

        return database.modify("UPDATE " + table + " SET " + assignments + conditions(), arguments);
    }

    /**
     * Удаляет подходящие строки. Без WHERE требуется явный вызов allRows.
     *
     * @return число удалённых строк
     * @throws IllegalStateException если нет WHERE и разрешения allRows
     * @throws DatabaseException при ошибке JDBC
     */
    public int delete() {
        requireWriteShape();
        requireScope();
        return database.modify("DELETE FROM " + table + conditions(), parameters);
    }

    /**
     * Защищает от случайного изменения всей таблицы без явного разрешения.
     *
     * @throws IllegalStateException если нет WHERE и явного allRows
     */
    private void requireScope() {
        if (predicates.isEmpty() && !allowAll) {
            throw new IllegalStateException("UPDATE/DELETE requires WHERE or explicit allRows()");
        }
    }

    /** Не допускает игнорирования ограничений SELECT при записи. */
    private void requireWriteShape() {
        if (limit != null || offset != 0 || !order.isEmpty() || !columns.equals("*")) {
            throw new IllegalStateException("Write queries cannot use projection, order or pagination");
        }
    }

    /**
     * Вставляет пакет атомарно. Все строки должны содержать одинаковые колонки.
     * @param rows от 1 до maxBatch строк; сгенерированные ключи не возвращаются
     */
    public void insertAll(List<? extends Map<String, Object>> rows) {
        requireWriteShape();
        if (!predicates.isEmpty() || rows.isEmpty() || rows.size() > database.options().maxBatch()) {
            throw new IllegalArgumentException("Invalid batch size or INSERT conditions");
        }
        List<String> names = List.copyOf(rows.getFirst().keySet());
        if (names.isEmpty()) {
            throw new IllegalArgumentException("INSERT values are required");
        }
        StringJoiner selected = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        for (String name : names) {
            selected.add(identifier(name));
            placeholders.add("?");
        }
        List<List<Object>> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!row.keySet().equals(rows.getFirst().keySet())) {
                throw new IllegalArgumentException("Batch columns must match");
            }
            List<Object> parameters = new ArrayList<>();
            for (String name : names) {
                parameters.add(row.get(name));
            }
            values.add(parameters);
        }
        database.insertBatch("INSERT INTO " + table + " (" + selected + ") VALUES (" + placeholders + ")", values);
    }

    /**
     * Собирает секцию WHERE из условий через AND, не подставляя значения в SQL.
     *
     * @return пустая строка либо WHERE с условиями через AND
     */
    private String conditions() {
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Проверяет имя таблицы или колонки и добавляет двойные кавычки.
     *
     * <p>Имена нельзя передать как параметры JDBC, поэтому они проверяются отдельно.
     *
     * @param name проверяемое имя таблицы или колонки
     * @return проверенный SQL-идентификатор в кавычках
     * @throws IllegalArgumentException если имя недопустимо
     */
    private String identifier(String name) {
        return identifier(database, name);
    }

    /**
     * Проверяет имя и добавляет кавычки диалекта.
     *
     * @param database объект с выбранным диалектом
     * @param name простое имя колонки или таблицы
     * @return проверенное имя в кавычках
     */
    private static String identifier(Database database, String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier");
        }
        return database.dialect().quote(name);
    }
}
