package core.contracts;

/** Хеширование паролей и проверка сохранённого хеша. */
public interface PasswordHasher {
    /**
     * Создаёт хеш со случайной солью.
     * @param password исходный пароль; массив остаётся у вызывающего кода
     * @return строка с алгоритмом, параметрами, солью и хешем
     */
    String hash(char[] password);

    /**
     * Проверяет пароль без изменения массива.
     * @param password введённый пароль
     * @param encoded сохранённый хеш
     * @return результат проверки; неверный формат возвращает false
     */
    boolean verify(char[] password, String encoded);
}
