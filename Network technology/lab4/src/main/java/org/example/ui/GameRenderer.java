package org.example.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import org.example.model.*;

import java.util.*;

/**
 * Вспомогательный класс для отрисовки игры
 */
public class GameRenderer {

    private final int cellSize;

    private static final Color FOOD_COLOR = Color.RED;
    private static final Color BACKGROUND_COLOR = Color.rgb(30, 30, 30);
    private static final Color GRID_COLOR = Color.rgb(50, 50, 50);

    private static final Color[] SNAKE_COLORS = {
            Color.LIME, Color.CYAN, Color.YELLOW, Color.MAGENTA,
            Color.ORANGE, Color.PINK, Color.LIGHTBLUE, Color.LIGHTGREEN,
            Color.CORAL, Color.AQUAMARINE, Color.GOLD, Color.VIOLET
    };

    public GameRenderer(int cellSize) {
        this.cellSize = cellSize;
    }

    public void render(GraphicsContext gc, GameState state, int myPlayerId) {
        int width = state.getConfig().getWidth();
        int height = state.getConfig().getHeight();

        // Фон
        gc.setFill(BACKGROUND_COLOR);
        gc.fillRect(0, 0, width * cellSize, height * cellSize);

        // Сетка
        drawGrid(gc, width, height);

        // Еда
        drawFood(gc, state.getFoods());

        // Змейки
        drawSnakes(gc, state, myPlayerId);
    }

    private void drawGrid(GraphicsContext gc, int width, int height) {
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);

        for (int x = 0; x <= width; x++) {
            gc.strokeLine(x * cellSize, 0, x * cellSize, height * cellSize);
        }
        for (int y = 0; y <= height; y++) {
            gc.strokeLine(0, y * cellSize, width * cellSize, y * cellSize);
        }
    }

    private void drawFood(GraphicsContext gc, Set<Coord> foods) {
        gc.setFill(FOOD_COLOR);
        for (Coord food : foods) {
            gc.fillOval(
                    food.getX() * cellSize + 2,
                    food.getY() * cellSize + 2,
                    cellSize - 4,
                    cellSize - 4
            );
        }
    }

    private void drawSnakes(GraphicsContext gc, GameState state, int myPlayerId) {
        Map<Integer, Color> playerColors = new HashMap<>();
        int colorIndex = 0;
        for (int id : state.getSnakes().keySet()) {
            playerColors.put(id, SNAKE_COLORS[colorIndex % SNAKE_COLORS.length]);
            colorIndex++;
        }

        int width = state.getConfig().getWidth();
        int height = state.getConfig().getHeight();

        for (Snake snake : state.getSnakes().values()) {
            Color baseColor = playerColors.get(snake.getPlayerId());
            Color color = snake.getState() == Snake.SnakeState.ZOMBIE
                    ? baseColor.deriveColor(0, 0.5, 0.7, 0.7)
                    : baseColor;

            List<Coord> cells = snake.getAllCells(width, height);

            for (int i = 0; i < cells.size(); i++) {
                Coord cell = cells.get(i);

                if (i == 0) {
                    // Голова
                    gc.setFill(color.brighter());
                    gc.fillRoundRect(
                            cell.getX() * cellSize + 1,
                            cell.getY() * cellSize + 1,
                            cellSize - 2,
                            cellSize - 2,
                            5, 5
                    );

                    // Глаза
                    gc.setFill(Color.BLACK);
                    drawEyes(gc, cell, snake.getHeadDirection());
                } else {
                    // Тело
                    gc.setFill(color);
                    gc.fillRoundRect(
                            cell.getX() * cellSize + 2,
                            cell.getY() * cellSize + 2,
                            cellSize - 4,
                            cellSize - 4,
                            3, 3
                    );
                }
            }

            // Выделение своей змейки
            if (snake.getPlayerId() == myPlayerId) {
                Coord head = snake.getHead();
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(2);
                gc.strokeRect(
                        head.getX() * cellSize,
                        head.getY() * cellSize,
                        cellSize,
                        cellSize
                );
            }
        }
    }

    private void drawEyes(GraphicsContext gc, Coord head, Direction direction) {
        double cx = head.getX() * cellSize + cellSize / 2.0;
        double cy = head.getY() * cellSize + cellSize / 2.0;
        double eyeSize = 3;
        double eyeOffset = 3;

        double eye1x, eye1y, eye2x, eye2y;

        switch (direction) {
            case UP -> {
                eye1x = cx - eyeOffset; eye1y = cy - eyeOffset;
                eye2x = cx + eyeOffset; eye2y = cy - eyeOffset;
            }
            case DOWN -> {
                eye1x = cx - eyeOffset; eye1y = cy + eyeOffset;
                eye2x = cx + eyeOffset; eye2y = cy + eyeOffset;
            }
            case LEFT -> {
                eye1x = cx - eyeOffset; eye1y = cy - eyeOffset;
                eye2x = cx - eyeOffset; eye2y = cy + eyeOffset;
            }
            case RIGHT -> {
                eye1x = cx + eyeOffset; eye1y = cy - eyeOffset;
                eye2x = cx + eyeOffset; eye2y = cy + eyeOffset;
            }
            default -> { return; }
        }

        gc.fillOval(eye1x - eyeSize / 2, eye1y - eyeSize / 2, eyeSize, eyeSize);
        gc.fillOval(eye2x - eyeSize / 2, eye2y - eyeSize / 2, eyeSize, eyeSize);
    }
}