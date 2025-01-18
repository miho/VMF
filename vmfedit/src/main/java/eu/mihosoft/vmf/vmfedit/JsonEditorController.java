package eu.mihosoft.vmf.vmfedit;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Controller class for a JSON editor component that uses a WebView to render and interact with
 * a JSON editor interface. This class provides functionality for editing JSON data with schema
 * validation and real-time updates.
 *
 * The controller manages bidirectional communication between JavaFX and JavaScript, handling
 * schema updates, value changes, and error conditions.
 */
public class JsonEditorController {

    /** The WebView component used to display the JSON editor */
    private final WebView webView;

    /** Property holding the current JSON schema */
    private final StringProperty schemaProperty = new SimpleStringProperty(
            "{\n" +
                    "  \"$schema\" : \"http://json-schema.org/draft-07/schema#\",\n" +
                    "  \"title\" : \"value\",\n" +
                    "  \"type\" : \"string\",\n" +
                    "  \"readOnly\": true,\n" +
                    "  \"default\": \"set a schema\"\n" +
                    "}");

    /** Property holding the current JSON value */
    private final StringProperty valueProperty = new SimpleStringProperty("");

    /**
     * Constructs a new JsonEditorController with the specified WebView.
     *
     * @param webView The WebView component to use for the JSON editor
     */
    public JsonEditorController(WebView webView) {
        this.webView = webView;
    }

    /**
     * Gets the current JSON schema.
     *
     * @return The current schema as a string
     */
    public String getSchema() {
        return schemaProperty.get();
    }

    /**
     * Sets a new JSON schema.
     *
     * @param schema The new schema to set
     */
    public void setSchema(String schema) {
        schemaProperty.set(schema);
    }

    /**
     * Gets the schema property for binding.
     *
     * @return The StringProperty representing the schema
     */
    public StringProperty schemaProperty() {
        return schemaProperty;
    }

    /**
     * Gets the current JSON value.
     *
     * @return The current value as a string
     */
    public String getValue() {
        return valueProperty.get();
    }

    /**
     * Sets a new JSON value.
     *
     * @param value The new value to set
     */
    public void setValue(String value) {
        valueProperty.set(value);
    }

    /**
     * Commits the last edited value. This ensures that the value is up-to-date with the editor. Do this if you save
     * the value without a user interaction, e.g., without losing focus.
     */
    public void commitValue() {
        String value = (String) webView.getEngine().executeScript("document.activeElement.blur(); JSON.stringify(getValue())");

        if(Platform.isFxApplicationThread()) {
            valueProperty.set(value);
        } else {
            CompletableFuture<Void> future = new CompletableFuture<>();
            Platform.runLater(() -> {
                valueProperty.set(value);
                future.complete(null);
            });
            future.join();
        }
    }

    /**
     * Gets the value property for binding.
     *
     * @return The StringProperty representing the value
     */
    public StringProperty valueProperty() {
        return valueProperty;
    }

    /**
     * Callback implementation for handling editor changes from JavaScript.
     */
    public static class MyEditorCallback implements Consumer<String> {
        private final JsonEditorController control;

        /**
         * Constructs a new callback for the specified controller.
         *
         * @param control The JsonEditorController instance
         */
        public MyEditorCallback(JsonEditorController control) {
            this.control = control;
        }

        @Override
        public void accept(String newValue) {
            new Thread(() -> {
                Platform.runLater(() -> {
                    control.valueProperty().set(newValue);
                });
            }).start();
        }
    }

    /**
     * Enumeration of log levels for the editor's logging system.
     */
    public enum LogLevel {
        DBG, INFO, WARN, ERROR
    }

    /**
     * Interface for logging events from the JSON editor.
     */
    @FunctionalInterface
    public interface LogListener {
        /**
         * Called when a log event occurs.
         *
         * @param level The severity level of the log
         * @param message The log message
         * @param ex Any associated exception (may be null)
         */
        void log(LogLevel level, String message, Exception ex);
    }

    /** Default log listener implementation */
    private LogListener logListener = (level, message, ex) -> {
        if(level == LogLevel.ERROR) {
            System.err.println("[" + level + "] " + message);
            if(ex!=null) ex.printStackTrace(System.err);
        } else {
            System.out.println("[" + level + "] " + message);
            if(ex!=null) ex.printStackTrace(System.out);
        }
    };

    /**
     * Sets a custom log listener for the editor.
     *
     * @param logListener The new log listener to use
     */
    public void setLogListener(LogListener logListener) {
        if (logListener == null) {
            logListener = this.logListener;
        }
        this.logListener = logListener;
    }

    /**
     * Initializes the JSON editor component. This method is called automatically by FXML.
     * It sets up the WebView, establishes JavaScript bridges, and configures property listeners.
     */
    @FXML
    public void initialize() {
        WebEngine engine = webView.getEngine();

        // Set up the web engine load listener
        setupWebEngineLoadListener(engine);

        // Set up schema change listener
        setupSchemaChangeListener();

        // Set up WebView focus handling
        setupWebViewFocusHandling();

        // Set up JavaScript event handlers
        setupJavaScriptEventHandlers(engine);

        // Set up value property change listener
        setupValueChangeListener();
    }

    /**
     * Sets up the web engine load listener to handle page load events.
     */
    private void setupWebEngineLoadListener(WebEngine engine) {
        engine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                logListener.log(LogLevel.INFO, "Page loaded successfully", null);
                initializeJavaScriptBridge(engine);
            } else if (newValue == Worker.State.FAILED) {
                logListener.log(LogLevel.ERROR, "Page failed to load", null);
            }
        });

        // Load the HTML file
        URL url = getClass().getResource("json-editor.html");
        if (url != null) {
            logListener.log(LogLevel.INFO, "Loading URL: " + url, null);
            engine.load(url.toExternalForm());
        } else {
            logListener.log(LogLevel.ERROR, "Could not find json-editor.html", null);
        }
    }

    private MyEditorCallback myEditorCallback = new MyEditorCallback(this);
    /**
     * Initializes the JavaScript bridge for communication between Java and JavaScript.
     */
    private void initializeJavaScriptBridge(WebEngine engine) {
        // Get our namespace object
        JSObject editorCallbacks = (JSObject) engine.executeScript("EditorCallbacks");
        // Set the callback using our namespace's method
        editorCallbacks.call("setHostCallback", myEditorCallback);
        engine.executeScript("updateSchema('" + escapeJavaScript(schemaProperty.get()) + "')");
    }

    /**
     * Sets up the schema change listener to handle schema updates.
     */
    private void setupSchemaChangeListener() {
        schemaProperty().addListener((observable, oldValue, newValue) -> {
            try {
                handleSchemaUpdate(newValue);
            } catch (Exception e) {
                logListener.log(LogLevel.ERROR, "Error loading schema: " + e.getMessage(), e);
                showError("Error loading schema", e.getMessage());
            }
        });
    }

    /**
     * Handles updating the schema and attempting to migrate existing values.
     */
    private void handleSchemaUpdate(String schema) {
        String value = (String) webView.getEngine().executeScript("JSON.stringify(getValue())");
        logListener.log(LogLevel.INFO, "Schema updated. Value will be reset and migration attempt will be made.", null);

        webView.getEngine().executeScript("updateSchema('" + escapeJavaScript(schema) + "')");

        if (value != null && !value.isEmpty() && !"\"\"".equals(value) && !"\"set a schema\"".equals(value)) {
            attemptValueMigration(value);
        }
    }

    /**
     * Attempts to migrate an existing value to a new schema.
     */
    private void attemptValueMigration(String value) {
        Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 10; i++) {
                if (trySetValue(value)) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * Attempts to set a value in the editor with proper UI thread handling.
     */
    private boolean trySetValue(String value) {
        var future = new CompletableFuture<Boolean>();
        Platform.runLater(() -> {
            try {
                var scene = webView.getScene();
                scene.getRoot().setDisable(true);
                webView.getEngine().executeScript("setValue('" + escapeJavaScript(value) + "')");
                future.complete(true);
            } catch (Exception e) {
                future.complete(false);
                logListener.log(LogLevel.ERROR, "Error setting value: " + e.getMessage(), e);
            } finally {
                var scene = webView.getScene();
                scene.getRoot().setDisable(false);
            }
        });
        return future.join();
    }

    /**
     * Sets up focus handling for the WebView component.
     */
    private void setupWebViewFocusHandling() {
        webView.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                webView.getEngine().executeScript("document.activeElement.blur();");
            }
        });
    }

    /**
     * Sets up JavaScript event handlers for the web engine.
     */
    private void setupJavaScriptEventHandlers(WebEngine engine) {
        engine.setOnAlert(event -> logListener.log(LogLevel.INFO, "JS Alert: " + event.getData(), null));
        engine.setOnError(event -> logListener.log(LogLevel.ERROR, "JS Error: " + event.getMessage(), null));
        engine.setOnStatusChanged(event -> logListener.log(LogLevel.INFO, "JS Status: " + event.getData(), null));
        engine.setOnResized(event -> logListener.log(LogLevel.INFO, "JS Resized: " + event.getData(), null));
        engine.setOnVisibilityChanged(event -> logListener.log(LogLevel.INFO, "JS Visibility Changed: " + event.getData(), null));
    }

    /**
     * Sets up the value change listener to handle value updates.
     */
    private void setupValueChangeListener() {
        valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty() && !"\"\"".equals(newValue)) {
                attemptValueMigration(newValue);
            }
        });
    }

    /**
     * Escapes special characters in JavaScript strings.
     *
     * @param str The string to escape
     * @return The escaped string
     */
    private String escapeJavaScript(String str) {
        return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Default error handler for the editor.
     */
    private BiConsumer<String, String> onErrorConsumer = (title, message) -> {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    };

    /**
     * Sets a custom error handler for the editor.
     *
     * @param onErrorConsumer The new error handler to use
     */
    public void setOnError(BiConsumer<String, String> onErrorConsumer) {
        if (onErrorConsumer != null) {
            this.onErrorConsumer = onErrorConsumer;
        }
    }

    /**
     * Shows an error dialog to the user.
     *
     * @param title The error dialog title
     * @param message The error message to display
     */
    private void showError(String title, String message) {
        onErrorConsumer.accept(title, message);
    }
}