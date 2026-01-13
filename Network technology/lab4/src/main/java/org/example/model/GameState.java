package org.example.model;

import org.example.game.NodeRole;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameState {
    private final GameConfig config;
    private int stateOrder;
    private final Map<Integer, Snake> snakes;
    private final Set<Coord> foods;
    private final Map<Integer, Player> players;
    private final Random random;

    public GameState(GameConfig config) {
        this.config = config;
        this.stateOrder = 0;
        this.snakes = new ConcurrentHashMap<>();
        this.foods = ConcurrentHashMap.newKeySet();
        this.players = new ConcurrentHashMap<>();
        this.random = new Random();
    }

    public GameConfig getConfig() { return config; }
    public int getStateOrder() { return stateOrder; }
    public void incrementStateOrder() { stateOrder++; }
    public Map<Integer, Snake> getSnakes() { return snakes; }
    public Set<Coord> getFoods() { return foods; }
    public Map<Integer, Player> getPlayers() { return players; }

    public void addSnake(Snake snake) {
        snakes.put(snake.getPlayerId(), snake);
    }

    public void removeSnake(int playerId) {
        snakes.remove(playerId);
    }

    public void addPlayer(Player player) {
        players.put(player.getId(), player);
    }

    public void removePlayer(int playerId) {
        players.remove(playerId);
    }

    public Player getPlayer(int playerId) {
        return players.get(playerId);
    }

    public Snake getSnake(int playerId) {
        return snakes.get(playerId);
    }

    /**
     * Подсчитывает количество ALIVE змеек
     */
    public int getAliveSnakesCount() {
        return (int) snakes.values().stream()
                .filter(s -> s.getState() == Snake.SnakeState.ALIVE)
                .count();
    }

    /**
     * Возвращает все занятые клетки
     */
    public Set<Coord> getOccupiedCells() {
        Set<Coord> occupied = new HashSet<>();
        for (Snake snake : snakes.values()) {
            occupied.addAll(snake.getAllCells(config.getWidth(), config.getHeight()));
        }
        return occupied;
    }

    /**
     * Ищет свободный квадрат 5x5 для новой змейки
     */
    public Coord findFreeSquare() {
        Set<Coord> occupied = getOccupiedCells();

        List<Coord> candidates = new ArrayList<>();
        for (int x = 0; x < config.getWidth(); x++) {
            for (int y = 0; y < config.getHeight(); y++) {
                if (isSquareFree(x, y, occupied)) {
                    candidates.add(new Coord(x + 2, y + 2)); // Центр квадрата
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean isSquareFree(int startX, int startY, Set<Coord> occupied) {
        for (int dx = 0; dx < 5; dx++) {
            for (int dy = 0; dy < 5; dy++) {
                Coord c = new Coord(startX + dx, startY + dy).normalize(config.getWidth(), config.getHeight());
                if (occupied.contains(c)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Добавляет еду на поле
     */
    public void spawnFood() {
        int required = config.getFoodStatic() + getAliveSnakesCount();
        Set<Coord> occupied = getOccupiedCells();
        occupied.addAll(foods);

        while (foods.size() < required) {
            List<Coord> free = new ArrayList<>();
            for (int x = 0; x < config.getWidth(); x++) {
                for (int y = 0; y < config.getHeight(); y++) {
                    Coord c = new Coord(x, y);
                    if (!occupied.contains(c)) {
                        free.add(c);
                    }
                }
            }

            if (free.isEmpty()) break;

            Coord newFood = free.get(random.nextInt(free.size()));
            foods.add(newFood);
            occupied.add(newFood);
        }
    }

    public GameState copy() {
        GameState copy = new GameState(config);
        copy.stateOrder = this.stateOrder;

        for (Snake snake : snakes.values()) {
            copy.snakes.put(snake.getPlayerId(), snake.copy());
        }

        copy.foods.addAll(this.foods);

        for (Player player : players.values()) {
            Player pCopy = new Player(player.getId(), player.getName(), player.getRole());
            pCopy.setScore(player.getScore());
            pCopy.setAddress(player.getAddress());
            copy.players.put(pCopy.getId(), pCopy);
        }

        return copy;
    }
}