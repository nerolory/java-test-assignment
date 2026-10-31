package app;

import core.errors.DatabaseException;

import app.contracts.Navigator;
import app.contracts.SessionService;
import app.controllers.WindowController;
import app.providers.AppServiceProvider;
import core.config.DatabaseConfig;
import core.container.ServiceContainer;
import core.contracts.PasswordHasher;
import core.contracts.SecretStore;
import core.database.Database;
import core.database.DatabaseConfigurator;
import core.database.DatabaseTasks;
import core.security.Pbkdf2PasswordHasher;
import core.security.WindowsSecretStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public class Main extends Application {
    private static final System.Logger LOG = System.getLogger(Main.class.getName());
    private DatabaseTasks tasks;
    private WindowController navigator;
    private SessionService sessions;
    private boolean restoreFailed;
    private Throwable startupError;
    private Path logDirectory;

    @Override
    public void init() {
        try {
            StartupPaths paths = StartupPaths.fromSystem();
            // Явно указанную папку не создаём: опечатка не должна оставлять папку с журналом.
            if (!paths.managedData() && !Files.isDirectory(paths.data())) {
                throw new NoSuchFileException(paths.data().toString(), null, "Data folder does not exist");
            }
            if (paths.packaged()) {
                logDirectory = logToFile(paths.data());
            }
            open(paths);
        } catch (Throwable error) {
            // У exe нет консоли: ошибку, включая Error (например, NoClassDefFoundError), покажет start().
            LOG.log(System.Logger.Level.ERROR, "Startup failed", error);
            startupError = error;
        }
    }

    private void open(StartupPaths paths) throws IOException {
        DatabaseConfig config = DatabaseConfig.load(paths.root().resolve("config/database.properties"), paths.data());
        if (paths.managedData()) {
            // exe создаёт базу при первом запуске, а на следующих только убирает остатки прерванных попыток.
            // При разработке и для явного app.data базу создаёт scripts/init-database.ps1.
            if (DatabaseConfigurator.createIfMissing(config, Files.readString(paths.root().resolve("database/schema.sql")))) {
                LOG.log(System.Logger.Level.INFO, "Database created: " + config.path());
            }
        }
        Database database = DatabaseConfigurator.configure(config);
        tasks = new DatabaseTasks(database);

        ServiceContainer services = new ServiceContainer();
        services.instance(Database.class, database);
        services.instance(Clock.class, Clock.systemUTC());
        services.instance(PasswordHasher.class, new Pbkdf2PasswordHasher());
        services.instance(SecretStore.class, new WindowsSecretStore(paths.data().resolve(".local/auth-session.bin")));
        services.instance(Executor.class, command -> tasks.submit(() -> { command.run(); return null; }));
        services.register(new AppServiceProvider());
        sessions = services.resolve(SessionService.class);
        navigator = new WindowController(services::resolve, sessions);
        services.instance(Navigator.class, navigator);
        try {
            sessions.restore();
        } catch (IOException | DatabaseException error) {
            LOG.log(System.Logger.Level.WARNING, "Session restore failed", error);
            restoreFailed = true;
        }
    }

    /**
     * Направляет журнал в data/logs: у exe нет консоли. Хранятся 3 файла по 1 МБ.
     * Второй запущенный экземпляр пишет в файл с суффиксом, поэтому возвращается папка.
     * @param data папка данных
     * @return папка журнала
     * @throws IOException если папку или файл журнала нельзя создать
     */
    private static Path logToFile(Path data) throws IOException {
        Path logs = Files.createDirectories(data.resolve("logs"));
        // FileHandler раскрывает %h, %t, %u и %g в любом месте шаблона, поэтому '%' из пути экранируется.
        String pattern = logs.toString().replace("%", "%%") + File.separator + "app%g.log";
        FileHandler handler = new FileHandler(pattern, 1_000_000, 3, true);
        handler.setEncoding("UTF-8");
        handler.setFormatter(new SimpleFormatter());
        Logger.getLogger("").addHandler(handler);
        return logs;
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        if (startupError != null) {
            // У ошибок файловой системы сообщение — только путь, поэтому добавляем тип.
            String reason = startupError.getClass().getSimpleName()
                    + (startupError.getMessage() == null ? "" : ": " + startupError.getMessage());
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    reason + (logDirectory == null ? "" : "\n\nЖурнал: " + logDirectory));
            alert.setTitle("Test App");
            alert.setHeaderText("Не удалось запустить приложение");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        primaryStage.setTitle("Test App");
        navigator.attach(primaryStage);
        navigator.show(sessions.current().isPresent() ? Navigator.Page.WELCOME : Navigator.Page.LOGIN);
        if (restoreFailed) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Не удалось восстановить сохранённый вход. Войдите повторно.").showAndWait();
        }
    }

    @Override
    public void stop() {
        if (tasks != null) {
            tasks.close();
        }
    }
}
