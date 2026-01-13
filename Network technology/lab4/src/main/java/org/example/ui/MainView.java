package org.example.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.example.game.GameController;
import org.example.model.GameConfig;

import java.util.List;

public class MainView {

    private final Stage stage;
    private final GameController controller;
    private GameView gameView;

    private ListView<GameController.GameInfo> gamesList;
    private TextField playerNameField;
    private TextField gameNameField;
    private TextField widthField;
    private TextField heightField;
    private TextField foodField;
    private TextField delayField;

    public MainView(Stage stage, GameController controller) {
        this.stage = stage;
        this.controller = controller;

        controller.setGamesListCallback(this::updateGamesList);
        controller.setErrorCallback(this::showError);
    }

    public void show() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        root.setAlignment(Pos.CENTER);

        // Имя игрока
        HBox nameBox = new HBox(10);
        nameBox.setAlignment(Pos.CENTER);
        nameBox.getChildren().addAll(
                new Label("Player Name:"),
                playerNameField = new TextField("Player" + System.currentTimeMillis() % 1000)
        );

        // Создание новой игры
        TitledPane newGamePane = createNewGamePane();

        // Список игр
        TitledPane gamesPane = createGamesListPane();

        root.getChildren().addAll(nameBox, newGamePane, gamesPane);

        Scene scene = new Scene(root, 600, 550);
        stage.setScene(scene);
        stage.show();

        // Запускаем обнаружение игр
        controller.startDiscovery();
    }

    private TitledPane createNewGamePane() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        grid.add(new Label("Game Name:"), 0, 0);
        grid.add(gameNameField = new TextField("Game" + System.currentTimeMillis() % 1000), 1, 0);

        grid.add(new Label("Width (10-100):"), 0, 1);
        grid.add(widthField = new TextField("40"), 1, 1);

        grid.add(new Label("Height (10-100):"), 0, 2);
        grid.add(heightField = new TextField("30"), 1, 2);

        grid.add(new Label("Food Static (0-100):"), 0, 3);
        grid.add(foodField = new TextField("1"), 1, 3);

        grid.add(new Label("State Delay (100-3000 ms):"), 0, 4);
        grid.add(delayField = new TextField("300"), 1, 4);

        Button startButton = new Button("Start New Game");
        startButton.setOnAction(e -> startNewGame());
        grid.add(startButton, 1, 5);

        return new TitledPane("New Game", grid);
    }

    private TitledPane createGamesListPane() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));

        gamesList = new ListView<>();
        gamesList.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(GameController.GameInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("%s - %dx%d - %d players %s",
                            item.getName(),
                            item.getConfig().getWidth(),
                            item.getConfig().getHeight(),
                            item.getPlayerCount(),
                            item.canJoin() ? "" : "(FULL)"));
                }
            }
        });
        gamesList.setPrefHeight(150);

        HBox buttons = new HBox(10);
        Button joinButton = new Button("Join Game");
        joinButton.setOnAction(e -> joinGame(false));
        Button watchButton = new Button("Watch Game");
        watchButton.setOnAction(e -> joinGame(true));
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> {
            // Перезапускаем discovery
            controller.stopDiscovery();
            controller.startDiscovery();
        });
        buttons.getChildren().addAll(joinButton, watchButton, refreshButton);

        vbox.getChildren().addAll(gamesList, buttons);

        TitledPane pane = new TitledPane("Available Games", vbox);
        pane.setExpanded(true);
        return pane;
    }

    private void startNewGame() {
        try {
            String playerName = playerNameField.getText().trim();
            String gameName = gameNameField.getText().trim();

            if (playerName.isEmpty() || gameName.isEmpty()) {
                showError("Please enter player name and game name");
                return;
            }

            int width = Integer.parseInt(widthField.getText());
            int height = Integer.parseInt(heightField.getText());
            int food = Integer.parseInt(foodField.getText());
            int delay = Integer.parseInt(delayField.getText());

            GameConfig config = new GameConfig(width, height, food, delay);

            controller.stopDiscovery();
            controller.startNewGame(playerName, gameName, config);

            showGameView();
        } catch (NumberFormatException e) {
            showError("Invalid number format");
        }
    }

    private void joinGame(boolean viewerOnly) {
        GameController.GameInfo selected = gamesList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showError("Please select a game from the list");
            return;
        }

        if (!selected.canJoin() && !viewerOnly) {
            showError("This game is full. Try watching instead.");
            return;
        }

        String playerName = playerNameField.getText().trim();
        if (playerName.isEmpty()) {
            showError("Please enter player name");
            return;
        }

        System.out.println("[UI] Joining game: " + selected.getName() + " at " + selected.getMasterAddress());

        controller.stopDiscovery();
        controller.joinGame(playerName, selected, viewerOnly);

        // Показываем игровой экран сразу, состояние придёт от сервера
        showGameView();
    }

    private void showGameView() {
        Platform.runLater(() -> {
            gameView = new GameView(stage, controller, this::returnToLobby);
            gameView.show();
        });
    }

    private void returnToLobby() {
        Platform.runLater(() -> {
            controller.startDiscovery();
            show();
        });
    }

    private void updateGamesList(List<GameController.GameInfo> games) {
        Platform.runLater(() -> {
            // Сохраняем текущий выбор
            GameController.GameInfo selected = gamesList.getSelectionModel().getSelectedItem();
            String selectedName = selected != null ? selected.getName() : null;

            gamesList.getItems().clear();
            gamesList.getItems().addAll(games);

            // Восстанавливаем выбор
            if (selectedName != null) {
                for (GameController.GameInfo game : games) {
                    if (game.getName().equals(selectedName)) {
                        gamesList.getSelectionModel().select(game);
                        break;
                    }
                }
            }
        });
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            System.err.println("[UI] Error: " + message);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }
}