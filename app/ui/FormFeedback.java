package app.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;

/** Показывает ошибки формы и снимает их при редактировании, не запуская валидацию. */
public final class FormFeedback {
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
    private static final String SUCCESS_STYLE = "auth-page__feedback--success";
    private static final Object STATE_KEY = new Object();
    private final Label label;
    private final Map<String, TextInputControl> fields = new LinkedHashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private String message = "";

    /** Читает декларации стандартных JavaFX-элементов из загруженного FXML. */
    private FormFeedback(Parent form) {
        List<Node> nodes = new ArrayList<>();
        collect(form, nodes);
        List<Label> labels = nodes.stream()
                .filter(node -> "true".equals(node.getProperties().get("validationFeedback")))
                .map(node -> {
                    if (!(node instanceof Label target)) {
                        throw new IllegalArgumentException("validationFeedback must mark a Label");
                    }
                    return target;
                }).toList();
        if (labels.size() != 1) {
            throw new IllegalArgumentException("A form must declare one validationFeedback label");
        }
        label = labels.getFirst();
        for (Node node : nodes) {
            Object name = node.getProperties().get("validationField");
            if (name != null) {
                if (!(node instanceof TextInputControl control) || !(name instanceof String key) || key.isBlank()) {
                    throw new IllegalArgumentException("validationField must name a text input");
                }
                if (fields.putIfAbsent(key, control) != null) {
                    throw new IllegalArgumentException("Duplicate validationField: " + key);
                }
            }
        }
        // Сначала проверяем весь шаблон, затем добавляем обработчики.
        fields.forEach((name, control) -> {
            Object dependency = control.getProperties().get("validationDependsOn");
            if (dependency != null && !fields.containsKey(dependency)) {
                throw new IllegalArgumentException("Unknown validationDependsOn: " + dependency);
            }
        });
        fields.forEach((name, control) -> {
            listen(control, name);
            Object dependency = control.getProperties().get("validationDependsOn");
            if (dependency != null) {
                listen(fields.get(dependency), name);
            }
        });
    }

    /** Состояние хранится у корня формы, отдельно для каждой загрузки шаблона. */
    private static FormFeedback state(Parent form) {
        Object existing = form.getProperties().get(STATE_KEY);
        if (existing instanceof FormFeedback feedback) {
            return feedback;
        }
        FormFeedback feedback = new FormFeedback(form);
        form.getProperties().put(STATE_KEY, feedback);
        return feedback;
    }

    private static void collect(Node node, List<Node> nodes) {
        nodes.add(node);
        if (node instanceof Parent parent && !(node instanceof TextInputControl)) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, nodes));
        }
    }

    private void listen(TextInputControl control, String name) {
        control.textProperty().addListener((observable, previous, value) -> {
            errors.remove(name);
            message = "";
            label.getStyleClass().remove(SUCCESS_STYLE);
            render();
        });
    }

    /** Заменяет ошибки результатом очередной проверки. */
    public static void showValidation(Parent form, Map<String, String> validationErrors) {
        FormFeedback feedback = state(form);
        feedback.reset();
        feedback.errors.putAll(validationErrors);
        feedback.render();
    }

    /** Показывает общую ошибку, которая исчезнет при изменении любого поля. */
    public static void showError(Parent form, String text) {
        FormFeedback feedback = state(form);
        feedback.reset();
        feedback.message = text;
        feedback.render();
    }

    /** Показывает подтверждение успешной операции. */
    public static void showSuccess(Parent form, String text) {
        showError(form, text);
        state(form).label.getStyleClass().add(SUCCESS_STYLE);
    }

    /** Снимает подсветку и сообщения перед новой отправкой формы. */
    public static void clear(Parent form) {
        state(form).reset();
    }

    private void reset() {
        errors.clear();
        message = "";
        label.getStyleClass().remove(SUCCESS_STYLE);
        render();
    }

    private void render() {
        fields.forEach((name, control) -> control.pseudoClassStateChanged(INVALID, errors.containsKey(name)));
        label.setText(errors.isEmpty() ? message : String.join("\n", errors.values()));
    }
}
