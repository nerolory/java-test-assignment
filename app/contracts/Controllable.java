package app.contracts;

/** Common lifecycle contract for FXML controllers. */
public interface Controllable {
    /** Called by FXMLLoader after FXML fields have been injected. */
    void initialize();
}
