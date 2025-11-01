package org.example;
import javafx.application.Application;
import javafx.stage.Stage;


public class Main extends Application {
    public static void main(String[] args) {
        launch();
    }

    @Override
    public void start(Stage primaryStage) {
        Controller controller = new Controller();
        controller.startGUI(primaryStage);
    }
}