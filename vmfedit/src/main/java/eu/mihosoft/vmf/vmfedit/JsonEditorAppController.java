package eu.mihosoft.vmf.vmfedit;

import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class JsonEditorAppController {

    @FXML
    private WebView webView;

    @FXML
    private TextField schemaField;

    private JsonEditorController jsonEditorControl;

    private File currentFile;

    @FXML
    public void initialize() {

        jsonEditorControl = new JsonEditorController(webView);
        jsonEditorControl.initialize();

        // on schemaField text change update schema
        schemaField.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                String schema = new String(Files.readAllBytes(new File(newValue).toPath()));

                String value = jsonEditorControl.getValue();
                jsonEditorControl.setSchema(schema);

            } catch (IOException e) {
                // showError("Error loading schema", e.getMessage());
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

        FileChooser.ExtensionFilter extFilterJSON = new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
        FileChooser.ExtensionFilter extFilterALL = new FileChooser.ExtensionFilter("All files (*.*)", "*.*");
        fileChooser.getExtensionFilters().addAll(extFilterJSON, extFilterALL);
        File file = fileChooser.showOpenDialog(webView.getScene().getWindow());
        if (file != null) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()));
                jsonEditorControl.setValue(content);

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

        jsonEditorControl.commitValue();

        if(currentFile != null) {
            try {
                String content = jsonEditorControl.getValue();
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
            FileChooser.ExtensionFilter extFilterJSON = new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
            FileChooser.ExtensionFilter extFilterALL = new FileChooser.ExtensionFilter("All files (*.*)", "*.*");
            fileChooser.getExtensionFilters().addAll(extFilterJSON, extFilterALL);
            File file = fileChooser.showSaveDialog(webView.getScene().getWindow());
            if (file != null) {
                try {
                    String content = jsonEditorControl.getValue();
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
                String content = jsonEditorControl.getValue();
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