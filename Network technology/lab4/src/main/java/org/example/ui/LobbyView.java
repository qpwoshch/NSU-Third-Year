package org.example.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.example.game.GameController;

import java.util.List;

/**
 * Вспомогательный класс для отображения списка игр в игровом экране
 */
public class LobbyView extends VBox {

    private final ListView<GameController.GameInfo> gamesList;
    private final GameController controller;

    public LobbyView(GameController controller) {
        this.controller = controller;

        setSpacing(10);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #2d2d2d;");

        Label titleLabel = new Label("Other Games:");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");

        gamesList = new ListView<>();
        gamesList.setPrefHeight(100);
        gamesList.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(GameController.GameInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("%s (%d)", item.getName(), item.getPlayerCount()));
                }
            }
        });

        getChildren().addAll(titleLabel, gamesList);

        controller.setGamesListCallback(this::updateList);
    }

    private void updateList(List<GameController.GameInfo> games) {
        Platform.runLater(() -> {
            gamesList.getItems().clear();
            gamesList.getItems().addAll(games);
        });
    }
}