package app.controllers;

import app.contracts.Controllable;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

public class HeaderController implements Controllable {
    @FXML
    private Label titleLabel;

    @FXML
    private StackPane actionContainer;

    @FXML
    @Override
    public void initialize() {
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
    }

    public void setActionButton(Button button) {
        actionContainer.getChildren().clear();
        if (button != null) {
            actionContainer.getChildren().add(button);
        }
    }
}
