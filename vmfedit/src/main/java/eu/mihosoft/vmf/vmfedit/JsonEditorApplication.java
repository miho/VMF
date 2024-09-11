package eu.mihosoft.vmf.vmfedit;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class JsonEditorApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(JsonEditorApplication.class.getResource("json-editor-view.fxml"));
        Parent n = fxmlLoader.load();

        JsonEditorController controller = fxmlLoader.getController();

        Scene scene = new Scene(n, 800, 600);

        stage.setTitle("VMF JSON Editor");
        stage.setScene(scene);
        stage.show();

    }

    public static void main(String[] args) {
        launch();
    }
}