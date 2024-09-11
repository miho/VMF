package eu.mihosoft.vmf.vmfedit;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextField;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

public class JsonEditorController {
    @FXML
    private WebView webView;
    @FXML
    private TextField schemaField;

    private File currentFile;

    @FXML
    public void initialize() {
        WebEngine engine = webView.getEngine();

        engine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                System.out.println("Page loaded successfully");
            } else if (newValue == Worker.State.FAILED) {
                System.out.println("Page failed to load");
            }
        });

        URL url = getClass().getResource("json-editor.html");
        if (url != null) {
            System.out.println("Loading URL: " + url);
            engine.load(url.toExternalForm());
        } else {
            System.out.println("Could not find json-editor.html");
        }

        engine.setOnAlert(event -> System.out.println("JS Alert: " + event.getData()));
        engine.setOnError(event -> System.out.println("JS Error: " + event.getMessage()));

        // on schemaField text change update schema
        schemaField.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                String schema = new String(Files.readAllBytes(new File(newValue).toPath()));
                // get current value from editor
                String value = (String) webView.getEngine().executeScript("JSON.stringify(getValue())");
                System.out.println("Schema updated: value=" + value);

                // update schema and re-set value
                webView.getEngine().executeScript("updateSchema('" + escapeJavaScript(schema) + "')");

                if (value != null && !value.isEmpty() && !"\"\"".equals(value)) {
                    Thread.ofVirtual().start(() -> {
                        for (int i = 0; i < 10; i++) {
                            // attempt to set value
                            var future = new CompletableFuture<Boolean>();
                                Platform.runLater(() -> {
                                    try {

                                        // disable stage interaction via blurring the content
                                        var scene = webView.getScene();
                                        scene.getRoot().setDisable(true);

                                        webView.getEngine().executeScript(
                                                "setValue('" + escapeJavaScript(value) + "')");
                                        future.complete(true);

                                        // enable stage interaction

                                    } catch (Exception e) {
                                        future.complete(false);
                                    } finally {
                                        var scene = webView.getScene();
                                        scene.getRoot().setDisable(false);
                                    }
                                });

                            if(future.join()) {
                                break;
                            } else {
//                                System.out.println("Attempt " + i + " failed");
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });

                }

            } catch (IOException e) {
                // showError("Error loading schema", e.getMessage());
            }
        });

        // make sure the WebView content does not keep focus, the JS code needs
        // to be notified about focus changes, otherwise some user changes won't be
        // applied correctly
        webView.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                // Execute JavaScript to trigger a blur event inside the WebView
                webView.getEngine().executeScript("document.activeElement.blur();");
            }
        });

    }

    @FXML
    private void handleLoadDocument() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open JSON Document");

        if (currentFile != null) {
            fileChooser.setInitialDirectory(currentFile.getParentFile());
        }

        FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
        fileChooser.getExtensionFilters().add(extFilter);
        File file = fileChooser.showOpenDialog(webView.getScene().getWindow());
        if (file != null) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                webView.getEngine().executeScript("setValue('" + escapeJavaScript(content) + "')");

                currentFile = file;
                // get stage and set title
                Stage stage = (Stage) webView.getScene().getWindow();
                stage.setTitle("VMF JSON Editor - " + currentFile.getName());
            } catch (IOException e) {
                showError("Error loading document", e.getMessage());
            }
        }
    }

    @FXML
    private void handleSaveDocument() {

        if(currentFile != null) {
            try {
                String content = (String) webView.getEngine().executeScript("JSON.stringify(getValue())");
                System.out.println("Saving document: " + content);
                Files.write(currentFile.toPath(), content.getBytes());

                // get stage and set title
                Stage stage = (Stage) webView.getScene().getWindow();
                stage.setTitle("VMF JSON Editor - " + currentFile.getName());

            } catch (IOException e) {
                showError("Error saving document", e.getMessage());
            }
            return;
        } else {
            FileChooser fileChooser = new FileChooser();
            FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
            fileChooser.getExtensionFilters().add(extFilter);
            fileChooser.setTitle("Save JSON Document");
            File file = fileChooser.showSaveDialog(webView.getScene().getWindow());
            if (file != null) {
                try {
                    String content = (String) webView.getEngine().executeScript("JSON.stringify(getValue())");
                    System.out.println("Saving document: " + content);
                    Files.write(file.toPath(), content.getBytes());

                    currentFile = file;
                    // get stage and set title
                    Stage stage = (Stage) webView.getScene().getWindow();
                    stage.setTitle("VMF JSON Editor - " + currentFile.getName());

                } catch (IOException e) {
                    showError("Error saving document", e.getMessage());
                }
            }
        }
    }

    @FXML
    private void handleSaveAsDocument() {
        FileChooser fileChooser = new FileChooser();

        if (currentFile != null) {
            fileChooser.setInitialDirectory(currentFile.getParentFile());
        }

        FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
        fileChooser.getExtensionFilters().add(extFilter);
        fileChooser.setTitle("Save JSON Document as");
        File file = fileChooser.showSaveDialog(webView.getScene().getWindow());

        if (file != null) {
            try {
                String content = (String) webView.getEngine().executeScript("JSON.stringify(getValue())");
                System.out.println("Saving document as: " + content);
                Files.write(file.toPath(), content.getBytes());

                currentFile = file;
                // get stage and set title
                Stage stage = (Stage) webView.getScene().getWindow();
                stage.setTitle("VMF JSON Editor - " + currentFile.getName());

            } catch (IOException e) {
                showError("Error saving document", e.getMessage());
            }
        }
    }

    @FXML
    private void handleQuit() {
        System.exit(0);
    }

    @FXML
    private void handleBrowseSchema() {

        FileChooser fileChooser = new FileChooser();

        // set current directory from schemaField
        File currentDir = new File(schemaField.getText()).getParentFile();
        if (currentDir != null) {
            fileChooser.setInitialDirectory(currentDir);
        } else {
            //
        }

        fileChooser.setTitle("Open JSON Schema");
        // set json extension filter
        FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter("JSON Schema files (*.json)", "*.json");
        fileChooser.getExtensionFilters().add(extFilter);
        File file = fileChooser.showOpenDialog(webView.getScene().getWindow());
        if (file != null) {
            schemaField.setText(file.getAbsolutePath());
        }
    }

    private String escapeJavaScript(String str) {
        return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }
}