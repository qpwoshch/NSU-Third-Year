package org.example.game;

import org.example.model.*;
import java.util.*;

public class GameLogic {

    private final Random random = new Random();

    /**
     * Выполняет один ход игры
     */
    public void tick(GameState state, Map<Integer, Direction> pendingMoves) {
        // 1. Применяем повороты
        for (Map.Entry<Integer, Direction> entry : pendingMoves.entrySet()) {
            Snake snake = state.getSnake(entry.getKey());
            if (snake != null && snake.getState() == Snake.SnakeState.ALIVE) {
                Direction newDir = entry.getValue();
                if (!newDir.isOpposite(snake.getHeadDirection())) {
                    snake.setHeadDirection(newDir);
                }
            }
        }

        int width = state.getConfig().getWidth();
        int height = state.getConfig().getHeight();

        // 2. Двигаем всех змеек, запоминаем новые позиции голов
        Map<Integer, Coord> newHeads = new HashMap<>();
        Map<Integer, Boolean> ateFood = new HashMap<>();

        for (Snake snake : state.getSnakes().values()) {
            Coord oldHead = snake.getHead();
            Coord newHead = new Coord(
                    oldHead.getX() + snake.getHeadDirection().getDx(),
                    oldHead.getY() + snake.getHeadDirection().getDy()
            ).normalize(width, height);

            boolean ate = state.getFoods().contains(newHead);
            if (ate) {
                state.getFoods().remove(newHead);
                Player player = state.getPlayer(snake.getPlayerId());
                if (player != null) {
                    player.addScore(1);
                }
            }

            newHeads.put(snake.getPlayerId(), newHead);
            ateFood.put(snake.getPlayerId(), ate);

            snake.move(width, height, ate);
        }

        // 3. Проверяем столкновения
        Set<Integer> dead = new HashSet<>();
        Map<Integer, Integer> collisionPoints = new HashMap<>(); // Кто получит очки за столкновение

        for (Snake snake : state.getSnakes().values()) {
            Coord head = snake.getHead();

            // Проверяем столкновение с другими змейками (или собой)
            for (Snake other : state.getSnakes().values()) {
                List<Coord> cells = other.getAllCells(width, height);

                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i).equals(head)) {
                        // Если это голова той же змейки - пропускаем
                        if (other.getPlayerId() == snake.getPlayerId() && i == 0) {
                            continue;
                        }

                        dead.add(snake.getPlayerId());

                        // Если врезались в чужую змейку - она получает очко
                        if (other.getPlayerId() != snake.getPlayerId()) {
                            collisionPoints.put(other.getPlayerId(),
                                    collisionPoints.getOrDefault(other.getPlayerId(), 0) + 1);
                        }
                        break;
                    }
                }
            }
        }

        // Проверяем столкновения голов
        Map<Coord, List<Integer>> headPositions = new HashMap<>();
        for (Map.Entry<Integer, Coord> entry : newHeads.entrySet()) {
            headPositions.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey());
        }

        for (List<Integer> ids : headPositions.values()) {
            if (ids.size() > 1) {
                dead.addAll(ids);
            }
        }

        // 4. Начисляем очки за столкновения
        for (Map.Entry<Integer, Integer> entry : collisionPoints.entrySet()) {
            if (!dead.contains(entry.getKey())) {
                Player player = state.getPlayer(entry.getKey());
                if (player != null) {
                    player.addScore(entry.getValue());
                }
            }
        }

        // 5. Обрабатываем смерти
        for (int playerId : dead) {
            Snake snake = state.getSnake(playerId);
            if (snake != null) {
                // Превращаем клетки в еду с вероятностью 0.5
                for (Coord cell : snake.getAllCells(width, height)) {
                    if (random.nextBoolean()) {
                        state.getFoods().add(cell);
                    }
                }
                state.removeSnake(playerId);

                // Меняем роль игрока на VIEWER
                Player player = state.getPlayer(playerId);
                if (player != null && player.getRole() != NodeRole.VIEWER) {
                    player.setRole(NodeRole.VIEWER);
                }
            }
        }

        // 6. Добавляем еду
        state.spawnFood();

        // 7. Увеличиваем номер состояния
        state.incrementStateOrder();
    }

    /**
     * Создаёт змейку для нового игрока
     */
    public Snake createSnakeForPlayer(GameState state, int playerId) {
        Coord center = state.findFreeSquare();
        if (center == null) {
            return null;
        }

        Direction[] directions = Direction.values();
        Direction dir = directions[random.nextInt(directions.length)];

        // Проверяем что хвост не попадёт на еду
        Coord tailPos = new Coord(
                center.getX() + dir.opposite().getDx(),
                center.getY() + dir.opposite().getDy()
        ).normalize(state.getConfig().getWidth(), state.getConfig().getHeight());

        if (state.getFoods().contains(center) || state.getFoods().contains(tailPos)) {
            state.getFoods().remove(center);
            state.getFoods().remove(tailPos);
        }

        return new Snake(playerId, center, dir);
    }
}