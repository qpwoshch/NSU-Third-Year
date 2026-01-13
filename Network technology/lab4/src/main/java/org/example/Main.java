package org.example;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.example.game.GameController;
import org.example.ui.MainView;

public class Main extends Application {

    private GameController gameController;

    @Override
    public void start(Stage primaryStage) {
        gameController = new GameController();
        MainView mainView = new MainView(primaryStage, gameController);

        primaryStage.setTitle("Snake Game");
        primaryStage.setOnCloseRequest(e -> {
            gameController.shutdown();
            Platform.exit();
        });

        mainView.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}