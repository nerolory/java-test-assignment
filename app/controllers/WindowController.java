package app.controllers;

import app.contracts.Navigator;
import app.contracts.SessionService;
import java.util.function.Function;
import java.io.IOException;
import java.util.Locale;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/** Управляет главным окном и загружает FXML через переданную фабрику контроллеров. */
public final class WindowController implements Navigator {
    private static final System.Logger LOG = System.getLogger(WindowController.class.getName());
    private final Function<Class<?>, Object> controllerFactory;
    private final SessionService sessions;
    private Stage stage;

    public WindowController(Function<Class<?>, Object> controllerFactory, SessionService sessions) {
        this.controllerFactory = controllerFactory;
        this.sessions = sessions;
    }

    public void attach(Stage stage) {
        this.stage = stage;
    }

    @Override
    public void show(Page page) {
        if (page == Page.WELCOME && sessions.current().isEmpty()) {
            page = Page.LOGIN;
        }
        String resource = "/fxml/pages/" + page.name().toLowerCase(Locale.ROOT) + ".fxml";
        FXMLLoader loader = new FXMLLoader(getClass().getResource(resource));
        loader.setControllerFactory(controllerFactory::apply);
        try {
            stage.setScene(new Scene(loader.load()));
            stage.sizeToScene();
            stage.show();
        } catch (IOException error) {
            LOG.log(System.Logger.Level.ERROR, "Could not load " + resource, error);
            new Alert(Alert.AlertType.ERROR, "Не удалось открыть страницу.").showAndWait();
        }
    }
}
