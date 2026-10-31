package core.contracts;

import java.io.IOException;
import java.util.Optional;

/** Защищённое локальное хранение одного секрета. */
public interface SecretStore {
    /**
     * Читает секрет. Вызывающий код очищает возвращённый массив после использования.
     * @return секрет либо пустой результат
     * @throws IOException если чтение или расшифровка не удались
     */
    Optional<byte[]> read() throws IOException;

    /**
     * Заменяет сохранённый секрет.
     * @param secret исходные байты; массив не изменяется
     * @throws IOException если защита или запись не удались
     */
    void write(byte[] secret) throws IOException;

    /**
     * Удаляет сохранённый секрет, если он существует.
     * @throws IOException если файл нельзя удалить
     */
    void delete() throws IOException;
}
