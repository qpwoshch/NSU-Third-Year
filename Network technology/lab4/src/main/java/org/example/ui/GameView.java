package org.example.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.example.game.GameController;
import org.example.game.NodeRole;
import org.example.model.*;

import java.util.*;

public class GameView {

    private static final int CELL_SIZE = 15;
    private static final Color FOOD_COLOR = Color.RED;
    private static final Color BACKGROUND_COLOR = Color.rgb(30, 30, 30);
    private static final Color GRID_COLOR = Color.rgb(50, 50, 50);

    private static final Color[] SNAKE_COLORS = {
            Color.LIME, Color.CYAN, Color.YELLOW, Color.MAGENTA,
            Color.ORANGE, Color.PINK, Color.LIGHTBLUE, Color.LIGHTGREEN
    };

    private final Stage stage;
    private final GameController controller;
    private final Runnable onExit;

    private Canvas canvas;
    private VBox playersBox;
    private Label statusLabel;

    public GameView(Stage stage, GameController controller, Runnable onExit) {
        this.stage = stage;
        this.controller = controller;
        this.onExit = onExit;

        controller.setStateUpdateCallback(this::updateState);
    }

    public void show() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");

        GameState state = controller.getGameState();
        int width = state != null ? state.getConfig().getWidth() : 40;
        int height = state != null ? state.getConfig().getHeight() : 30;

        canvas = new Canvas(width * CELL_SIZE, height * CELL_SIZE);

        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setStyle("-fx-background-color: #1e1e1e;");
        canvasContainer.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(canvasContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: #1e1e1e;");

        root.setCenter(scrollPane);

        VBox rightPanel = new VBox(10);
        rightPanel.setPadding(new Insets(10));
        rightPanel.setStyle("-fx-background-color: #2d2d2d;");
        rightPanel.setPrefWidth(200);

        Label playersLabel = new Label("Players:");
        playersLabel.setTextFill(Color.WHITE);
        playersLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");

        playersBox = new VBox(5);

        statusLabel = new Label("");
        statusLabel.setTextFill(Color.LIGHTGRAY);

        Button exitButton = new Button("Leave Game");
        exitButton.setOnAction(e -> exitGame());
        exitButton.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        rightPanel.getChildren().addAll(playersLabel, playersBox, statusLabel, spacer, exitButton);

        root.setRight(rightPanel);

        Scene scene = new Scene(root, 900, 650);
        scene.setOnKeyPressed(e -> handleKeyPress(e.getCode()));

        stage.setScene(scene);

        if (state != null) {
            render(state);
            updatePlayersPanel(state);
        }
    }

    private void handleKeyPress(KeyCode code) {
        Direction direction = switch (code) {
            case W, UP -> Direction.UP;
            case S, DOWN -> Direction.DOWN;
            case A, LEFT -> Direction.LEFT;
            case D, RIGHT -> Direction.RIGHT;
            default -> null;
        };

        if (direction != null) {
            controller.steer(direction);
        }

        if (code == KeyCode.ESCAPE) {
            exitGame();
        }
    }

    private void updateState(GameState state) {
        Platform.runLater(() -> {
            render(state);
            updatePlayersPanel(state);
            updateStatus();
        });
    }

    private void render(GameState state) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        int width = state.getConfig().getWidth();
        int height = state.getConfig().getHeight();

        if (canvas.getWidth() != width * CELL_SIZE) {
            canvas.setWidth(width * CELL_SIZE);
        }
        if (canvas.getHeight() != height * CELL_SIZE) {
            canvas.setHeight(height * CELL_SIZE);
        }

        // Фон
        gc.setFill(BACKGROUND_COLOR);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Сетка
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        for (int x = 0; x <= width; x++) {
            gc.strokeLine(x * CELL_SIZE, 0, x * CELL_SIZE, height * CELL_SIZE);
        }
        for (int y = 0; y <= height; y++) {
            gc.strokeLine(0, y * CELL_SIZE, width * CELL_SIZE, y * CELL_SIZE);
        }

        // Еда
        gc.setFill(FOOD_COLOR);
        for (Coord food : state.getFoods()) {
            gc.fillOval(
                    food.getX() * CELL_SIZE + 2,
                    food.getY() * CELL_SIZE + 2,
                    CELL_SIZE - 4,
                    CELL_SIZE - 4
            );
        }

        // Змейки
        Map<Integer, Color> playerColors = assignColors(state.getSnakes().keySet());

        for (Snake snake : state.getSnakes().values()) {
            Color color = playerColors.get(snake.getPlayerId());
            if (snake.getState() == Snake.SnakeState.ZOMBIE) {
                color = color.deriveColor(0, 0.5, 0.7, 0.7);
            }

            List<Coord> cells = snake.getAllCells(width, height);

            for (int i = 0; i < cells.size(); i++) {
                Coord cell = cells.get(i);

                if (i == 0) {
                    gc.setFill(color.brighter());
                    gc.fillRoundRect(
                            cell.getX() * CELL_SIZE + 1,
                            cell.getY() * CELL_SIZE + 1,
                            CELL_SIZE - 2,
                            CELL_SIZE - 2,
                            5, 5
                    );

                    gc.setFill(Color.BLACK);
                    drawEyes(gc, cell, snake.getHeadDirection());
                } else {
                    gc.setFill(color);
                    gc.fillRoundRect(
                            cell.getX() * CELL_SIZE + 2,
                            cell.getY() * CELL_SIZE + 2,
                            CELL_SIZE - 4,
                            CELL_SIZE - 4,
                            3, 3
                    );
                }
            }
        }

        // Выделяем свою змейку
        Snake mySnake = state.getSnake(controller.getMyId());
        if (mySnake != null) {
            Coord head = mySnake.getHead();
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(2);
            gc.strokeRect(
                    head.getX() * CELL_SIZE,
                    head.getY() * CELL_SIZE,
                    CELL_SIZE,
                    CELL_SIZE
            );
        }
    }

    private void drawEyes(GraphicsContext gc, Coord head, Direction direction) {
        double cx = head.getX() * CELL_SIZE + CELL_SIZE / 2.0;
        double cy = head.getY() * CELL_SIZE + CELL_SIZE / 2.0;
        double eyeSize = 3;
        double eyeOffset = 3;

        double eye1x, eye1y, eye2x, eye2y;

        switch (direction) {
            case UP -> {
                eye1x = cx - eyeOffset;
                eye1y = cy - eyeOffset;
                eye2x = cx + eyeOffset;
                eye2y = cy - eyeOffset;
            }
            case DOWN -> {
                eye1x = cx - eyeOffset;
                eye1y = cy + eyeOffset;
                eye2x = cx + eyeOffset;
                eye2y = cy + eyeOffset;
            }
            case LEFT -> {
                eye1x = cx - eyeOffset;
                eye1y = cy - eyeOffset;
                eye2x = cx - eyeOffset;
                eye2y = cy + eyeOffset;
            }
            case RIGHT -> {
                eye1x = cx + eyeOffset;
                eye1y = cy - eyeOffset;
                eye2x = cx + eyeOffset;
                eye2y = cy + eyeOffset;
            }
            default -> {
                return;
            }
        }

        gc.fillOval(eye1x - eyeSize / 2, eye1y - eyeSize / 2, eyeSize, eyeSize);
        gc.fillOval(eye2x - eyeSize / 2, eye2y - eyeSize / 2, eyeSize, eyeSize);
    }

    private Map<Integer, Color> assignColors(Set<Integer> playerIds) {
        Map<Integer, Color> colors = new HashMap<>();
        int index = 0;
        for (int id : playerIds) {
            colors.put(id, SNAKE_COLORS[index % SNAKE_COLORS.length]);
            index++;
        }
        return colors;
    }

    private void updatePlayersPanel(GameState state) {
        if (state == null) return;

        playersBox.getChildren().clear();

        Map<Integer, Color> playerColors = assignColors(state.getSnakes().keySet());

        List<Player> sortedPlayers = new ArrayList<>(state.getPlayers().values());
        sortedPlayers.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));

        int myId = controller.getMyId();

        for (Player player : sortedPlayers) {
            HBox playerRow = new HBox(5);
            playerRow.setAlignment(Pos.CENTER_LEFT);

            // Цветной квадратик (серый для VIEWER)
            Region colorBox = new Region();
            colorBox.setMinSize(12, 12);
            colorBox.setMaxSize(12, 12);

            Color color;
            if (player.getRole() == NodeRole.VIEWER) {
                color = Color.GRAY;
            } else {
                color = playerColors.getOrDefault(player.getId(), Color.GRAY);
            }

            colorBox.setStyle(String.format("-fx-background-color: #%02x%02x%02x; -fx-background-radius: 2;",
                    (int) (color.getRed() * 255),
                    (int) (color.getGreen() * 255),
                    (int) (color.getBlue() * 255)));

            // Имя
            String nameText = player.getName();
            if (player.getId() == myId) {
                nameText += " (You)";
            }

            Label nameLabel = new Label(nameText);
            nameLabel.setTextFill(Color.WHITE);
            nameLabel.setMaxWidth(80);

            // Роль
            String roleText = switch (player.getRole()) {
                case MASTER -> "★ MASTER";
                case DEPUTY -> "◆ DEPUTY";
                case NORMAL -> "● NORMAL";
                case VIEWER -> "○ VIEWER";
            };

            Label roleLabel = new Label(roleText);
            roleLabel.setMinWidth(70);
            roleLabel.setTextFill(switch (player.getRole()) {
                case MASTER -> Color.GOLD;
                case DEPUTY -> Color.CYAN;
                case NORMAL -> Color.LIGHTGREEN;
                case VIEWER -> Color.GRAY;
            });
            roleLabel.setStyle("-fx-font-size: 10px;");

            // Очки
            Label scoreLabel = new Label(String.valueOf(player.getScore()));
            scoreLabel.setTextFill(Color.LIGHTGREEN);
            scoreLabel.setStyle("-fx-font-weight: bold;");
            scoreLabel.setMinWidth(30);
            scoreLabel.setAlignment(Pos.CENTER_RIGHT);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            playerRow.getChildren().addAll(colorBox, nameLabel, roleLabel, spacer, scoreLabel);
            playersBox.getChildren().add(playerRow);
        }
    }

    private void updateStatus() {
        NodeRole role = controller.getMyRole();
        String roleStr = switch (role) {
            case MASTER -> "MASTER";
            case DEPUTY -> "DEPUTY";
            case NORMAL -> "NORMAL";
            case VIEWER -> "VIEWER";
            default -> "Unknown";
        };

        statusLabel.setText("Role: " + roleStr);
    }

    private void exitGame() {
        controller.leaveGame();
        controller.startDiscovery();
        onExit.run();
    }
}