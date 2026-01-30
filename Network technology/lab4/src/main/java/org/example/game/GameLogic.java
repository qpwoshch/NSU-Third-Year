package org.example.game;

import org.example.model.*;

import java.util.*;

public class GameLogic {

    private final Random random = new Random();

    public List<Integer> tick(GameState state, Map<Integer, Direction> pendingMoves) {
        List<Integer> deadPlayers = new ArrayList<>();

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

        Map<Integer, Coord> newHeads = new HashMap<>();
        Map<Coord, List<Integer>> headsPerCell = new HashMap<>();
        Set<Coord> foodCellsToRemove = new HashSet<>();

        for (Snake snake : state.getSnakes().values()) {
            if (snake.getState() != Snake.SnakeState.ALIVE) continue;

            Coord oldHead = snake.getHead();
            Coord newHead = new Coord(
                    oldHead.getX() + snake.getHeadDirection().getDx(),
                    oldHead.getY() + snake.getHeadDirection().getDy()
            ).normalize(width, height);

            newHeads.put(snake.getPlayerId(), newHead);
            headsPerCell.computeIfAbsent(newHead, k -> new ArrayList<>()).add(snake.getPlayerId());

            if (state.getFoods().contains(newHead)) {
                foodCellsToRemove.add(newHead);
            }
        }

        Map<Integer, Boolean> ateFood = new HashMap<>();

        for (Map.Entry<Integer, Coord> entry : newHeads.entrySet()) {
            int playerId = entry.getKey();
            Coord head = entry.getValue();
            boolean willEat = foodCellsToRemove.contains(head);
            ateFood.put(playerId, willEat);

            if (willEat) {
                Player player = state.getPlayer(playerId);
                if (player != null) {
                    player.addScore(1);
                }
            }
        }

        for (Coord c : foodCellsToRemove) {
            state.getFoods().remove(c);
        }

        for (Snake snake : state.getSnakes().values()) {
            if (snake.getState() != Snake.SnakeState.ALIVE) continue;
            boolean grew = ateFood.getOrDefault(snake.getPlayerId(), false);
            snake.move(width, height, grew);
        }

        Set<Integer> dead = new HashSet<>();
        Map<Integer, Integer> collisionPoints = new HashMap<>();

        for (Snake snake : state.getSnakes().values()) {
            Coord head = snake.getHead();

            for (Snake other : state.getSnakes().values()) {
                List<Coord> cells = other.getAllCells(width, height);

                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i).equals(head)) {
                        if (other.getPlayerId() == snake.getPlayerId() && i == 0) {
                            continue;
                        }

                        dead.add(snake.getPlayerId());

                        if (other.getPlayerId() != snake.getPlayerId()) {
                            collisionPoints.merge(other.getPlayerId(), 1, Integer::sum);
                        }
                        break;
                    }
                }
            }
        }

        Map<Coord, List<Integer>> headPositions = new HashMap<>();
        for (Map.Entry<Integer, Coord> entry : newHeads.entrySet()) {
            headPositions.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        for (List<Integer> ids : headPositions.values()) {
            if (ids.size() > 1) {
                dead.addAll(ids);
            }
        }

        for (Map.Entry<Integer, Integer> entry : collisionPoints.entrySet()) {
            if (!dead.contains(entry.getKey())) {
                Player player = state.getPlayer(entry.getKey());
                if (player != null) {
                    player.addScore(entry.getValue());
                }
            }
        }

        for (int playerId : dead) {
            Snake snake = state.getSnake(playerId);
            if (snake != null) {
                for (Coord cell : snake.getAllCells(width, height)) {
                    if (random.nextBoolean()) {
                        state.getFoods().add(cell);
                    }
                }
                state.removeSnake(playerId);
                deadPlayers.add(playerId);
            }
        }

        state.spawnFood();

        state.incrementStateOrder();

        return deadPlayers;
    }

    public Snake createSnakeForPlayer(GameState state, int playerId) {
        int width = state.getConfig().getWidth();
        int height = state.getConfig().getHeight();
        Set<Coord> occupiedByFood = new HashSet<>(state.getFoods());
        Set<Coord> occupiedBySnakes = state.getOccupiedCells();

        for (int attempt = 0; attempt < 100; attempt++) {
            Coord center = state.findFreeSquare();
            if (center == null) {
                return null;
            }

            Direction[] directions = Direction.values();
            Direction dir = directions[random.nextInt(directions.length)];

            Coord tailPos = new Coord(
                    center.getX() + dir.opposite().getDx(),
                    center.getY() + dir.opposite().getDy()
            ).normalize(width, height);

            if (!occupiedByFood.contains(center) &&
                    !occupiedByFood.contains(tailPos) &&
                    !occupiedBySnakes.contains(tailPos)) {
                return new Snake(playerId, center, dir);
            }
        }
        return null;
    }
}