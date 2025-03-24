package eu.mihosoft.vmf.vmfedit;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Optional;


public class JsonEditorAppController {

    @FXML
    private WebView webView;

    @FXML
    private TextField schemaField;
    @FXML
    private Button browseSchemaButton;

    private JsonEditorController jsonEditorControl;

    private File currentFile;

    private JsonUtils.SchemaInfo schemaInfo;

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
    private void handleNewDocument() {
        // ask user whether to save current document or not (yes/no/cancel)
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Save Document");
        alert.setHeaderText("Do you want to save the current document?");
        alert.setContentText("Choose your option.");

        // Set up the button types
        ButtonType buttonTypeYes = new ButtonType("Yes");
        ButtonType buttonTypeNo = new ButtonType("No");
        ButtonType buttonTypeCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo, buttonTypeCancel);

        // Show the dialog and wait for response
        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent()) {
            if (result.get() == buttonTypeYes) {
                // User chose Yes, save the document
                handleSaveDocument();
            } else if (result.get() == buttonTypeCancel) {
                // User chose Cancel, do nothing and return
                return;
            }
            // If user chose No, continue with creating new document
        }

        // clear editor
        schemaField.setText("");
        schemaField.setDisable(false);
        browseSchemaButton.setDisable(false);
        currentFile = null;
        jsonEditorControl.reset();
        // get stage and set title
        Stage stage = (Stage) webView.getScene().getWindow();
        stage.setTitle("VMF JSON Editor");
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

                // remove comments
                content = content.replaceAll("//.*", "");

                // baseURI from parent directory
                URI baseURI = file.getParentFile().toURI();

                JsonUtils.SchemaInfo schemaInfo = JsonUtils.extractSchema(content, baseURI);

                if (schemaInfo.schemaUri() != null) {
                    schemaField.setDisable(true);
                    browseSchemaButton.setDisable(true);
                    schemaField.setText(schemaInfo.schemaUri().toString());
                    this.schemaInfo = schemaInfo;
                } else if (schemaInfo.schemaContent() != null) {
                    schemaField.setDisable(true);
                    browseSchemaButton.setDisable(true);
                    jsonEditorControl.setSchema(schemaInfo.schemaContent());
                    this.schemaInfo = schemaInfo;
                } else {
                    schemaField.setDisable(false);
                    browseSchemaButton.setDisable(false);
                    if(this.schemaInfo != null && this.schemaInfo.schemaUri() != null) {
                        // convert to local filename, removing file:: prefix
                        var url = this.schemaInfo.schemaUri().toURL().toString();
                        if (url.startsWith("file:")) {
                            url = url.substring(5);
                        }
                        schemaField.setText(url);
                    } else {
                        jsonEditorControl.setSchema(null);
                    }
                }

                String finalContent = content;

                Platform.runLater(() -> {
                    jsonEditorControl.setValue(finalContent);
                    currentFile = file;
                    // get stage and set title
                    Stage stage = (Stage) webView.getScene().getWindow();
                    stage.setTitle("VMF JSON Editor - " + currentFile.getName());
                });

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

                // add schema as was specified in the loaded file (as URI or content, see JsonUtils.extractSchema)
                if (this.schemaInfo!=null) {
                    content = JsonUtils.injectSchema(content, this.schemaInfo);
                }

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
            FileChooser.ExtensionFilter extFilterJSON =
                    new FileChooser.ExtensionFilter("JSON files (*.json)", "*.json");
            FileChooser.ExtensionFilter extFilterALL =
                    new FileChooser.ExtensionFilter("All files (*.*)", "*.*");
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

        FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter(
                "JSON files (*.json)", "*.json");
        fileChooser.getExtensionFilters().add(extFilter);
        fileChooser.setTitle("Save JSON Document as");
        File file = fileChooser.showSaveDialog(webView.getScene().getWindow());

        if (file != null) {
            try {
                String content = jsonEditorControl.getValue();
                System.out.println("Saving document as: " + content);

                // add schema as was specified in the loaded file (as URI or content, see JsonUtils.extractSchema)
                if (this.schemaInfo!=null) {
                    content = JsonUtils.injectSchema(content, this.schemaInfo);
                }

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
        FileChooser.ExtensionFilter extFilter = new FileChooser.ExtensionFilter(
                "JSON Schema files (*.json)", "*.json");
        fileChooser.getExtensionFilters().add(extFilter);
        File file = fileChooser.showOpenDialog(webView.getScene().getWindow());
        if (file != null) {
            schemaField.setText(file.getAbsolutePath());
            // update schemaInfo
            this.schemaInfo = new JsonUtils.SchemaInfo(
                    file.toURI(), null, JsonUtils.SchemaEmbeddingType.EXTERNAL);
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