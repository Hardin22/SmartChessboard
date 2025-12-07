package org.example.javachess.Application;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // Carica il file FXML
            // Carica il file FXML
            var resource = App.class.getResource("/UI/MainLayout.fxml");
            System.out.println("Resource URL: " + resource);
            if (resource == null) {
                throw new IllegalStateException("Cannot find /UI/MainLayout.fxml");
            }
            FXMLLoader fxmlLoader = new FXMLLoader(resource);
            Scene scene = new Scene(fxmlLoader.load(), 720, 1280);
            Font.loadFont(App.class.getResource("/Font/Poppins/Poppins-Regular.ttf").toExternalForm(), 10);
            Font.loadFont(App.class.getResource("/Font/Poppins/Poppins-Medium.ttf").toExternalForm(), 10);
            Font.loadFont(App.class.getResource("/Font/Poppins/Poppins-Bold.ttf").toExternalForm(), 10);

            // Configura la scena
            primaryStage.setScene(scene);
            scene.getStylesheets().add(App.class.getResource("/Styles/Style.css").toExternalForm());
            primaryStage.setTitle("Chess Application");
            primaryStage.setFullScreen(true);

            // Seleziona lo schermo desiderato (ad esempio, il secondo schermo)


            // Posiziona la finestra sullo schermo selezionato

            primaryStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}