package app;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Папки запуска приложения.
 *
 * @param root конфигурация и схема БД; у собранного exe может быть доступна только для чтения
 * @param data БД, файл сессии и журнал
 * @param packaged запуск через лаунчер jpackage (у exe нет консоли)
 * @param managedData папка данных выбрана приложением, а не указана явно; только в ней создаётся новая БД
 */
public record StartupPaths(Path root, Path data, boolean packaged, boolean managedData) {
    /**
     * Читает системные свойства и переменные окружения текущего процесса.
     * @return папки запуска
     */
    public static StartupPaths fromSystem() {
        return resolve(System::getProperty, System::getenv);
    }

    /**
     * Определяет папки: явный app.data, %LOCALAPPDATA%\&lt;имя exe&gt; для exe или корень проекта.
     * @param property системные свойства
     * @param environment переменные окружения
     * @return папки запуска
     */
    public static StartupPaths resolve(UnaryOperator<String> property, UnaryOperator<String> environment) {
        String rootValue = property.apply("app.root");
        Path root = Path.of(rootValue == null ? "." : rootValue).toAbsolutePath().normalize();
        // Лаунчер jpackage задаёт полный путь к exe; при запуске через java.exe свойства нет.
        String launcher = property.apply("jpackage.app-path");
        boolean packaged = launcher != null;
        String explicit = property.apply("app.data");
        if (explicit != null && !explicit.isBlank()) {
            return new StartupPaths(root, Path.of(explicit.strip()).toAbsolutePath().normalize(), packaged, false);
        }
        if (!packaged) {
            return new StartupPaths(root, root, false, false);
        }
        return new StartupPaths(root, localAppData(property, environment).resolve(appName(launcher)), true, true);
    }

    /** Имя папки данных совпадает с именем exe, которое задаёт scripts/package.ps1. */
    private static String appName(String launcher) {
        String file = Path.of(launcher).getFileName().toString();
        return file.toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? file.substring(0, file.length() - 4) : file;
    }

    /** Пустая или относительная переменная дала бы папку относительно текущего каталога лаунчера. */
    private static Path localAppData(UnaryOperator<String> property, UnaryOperator<String> environment) {
        String local = environment.apply("LOCALAPPDATA");
        if (local != null && !local.isBlank() && Path.of(local.strip()).isAbsolute()) {
            return Path.of(local.strip()).normalize();
        }
        return Path.of(property.apply("user.home"), "AppData", "Local").toAbsolutePath().normalize();
    }
}
