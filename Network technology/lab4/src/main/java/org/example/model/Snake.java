package org.example.model;

import java.util.ArrayList;
import java.util.List;

public class Snake {

    public enum SnakeState {
        ALIVE, ZOMBIE
    }

    private final int playerId;
    private List<Coord> keyPoints; // Первая точка - голова, остальные - смещения
    private SnakeState state;
    private Direction headDirection;

    public Snake(int playerId, Coord head, Direction direction) {
        this.playerId = playerId;
        this.keyPoints = new ArrayList<>();
        this.keyPoints.add(head.copy());
        // Хвост - смещение от головы в противоположном направлении
        this.keyPoints.add(direction.opposite().toCoord());
        this.state = SnakeState.ALIVE;
        this.headDirection = direction;
    }

    public Snake(int playerId, List<Coord> keyPoints, SnakeState state, Direction direction) {
        this.playerId = playerId;
        this.keyPoints = new ArrayList<>(keyPoints);
        this.state = state;
        this.headDirection = direction;
    }

    public int getPlayerId() { return playerId; }
    public SnakeState getState() { return state; }
    public void setState(SnakeState state) { this.state = state; }
    public Direction getHeadDirection() { return headDirection; }
    public void setHeadDirection(Direction direction) { this.headDirection = direction; }
    public List<Coord> getKeyPoints() { return keyPoints; }

    public Coord getHead() {
        return keyPoints.get(0);
    }

    /**
     * Разворачивает ключевые точки в полный список клеток змейки
     */
    public List<Coord> getAllCells(int width, int height) {
        List<Coord> cells = new ArrayList<>();
        Coord current = keyPoints.get(0).copy();
        cells.add(current.normalize(width, height));

        for (int i = 1; i < keyPoints.size(); i++) {
            Coord offset = keyPoints.get(i);
            int steps = Math.abs(offset.getX()) + Math.abs(offset.getY());
            int dx = offset.getX() == 0 ? 0 : offset.getX() / Math.abs(offset.getX());
            int dy = offset.getY() == 0 ? 0 : offset.getY() / Math.abs(offset.getY());

            for (int s = 0; s < steps; s++) {
                current = new Coord(current.getX() + dx, current.getY() + dy);
                cells.add(current.normalize(width, height));
            }
        }

        return cells;
    }

    /**
     * Двигает голову в текущем направлении, возвращает освободившуюся клетку хвоста или null если съели еду
     */
    public Coord move(int width, int height, boolean ateFood) {
        // Новая позиция головы
        Coord oldHead = keyPoints.get(0);
        Coord newHead = new Coord(
                oldHead.getX() + headDirection.getDx(),
                oldHead.getY() + headDirection.getDy()
        ).normalize(width, height);

        // Вычисляем старый хвост до движения
        Coord oldTail = null;
        if (!ateFood) {
            List<Coord> cells = getAllCells(width, height);
            oldTail = cells.get(cells.size() - 1);
        }

        // Обновляем ключевые точки
        // Первое смещение после головы может измениться
        if (keyPoints.size() > 1) {
            Coord firstOffset = keyPoints.get(1);
            Direction firstDir = getDirectionFromOffset(firstOffset);

            if (firstDir == headDirection.opposite()) {
                // Увеличиваем первое смещение
                int newDx = firstOffset.getX() + headDirection.opposite().getDx();
                int newDy = firstOffset.getY() + headDirection.opposite().getDy();
                keyPoints.set(1, new Coord(newDx, newDy));
            } else {
                // Добавляем новое смещение
                keyPoints.add(1, headDirection.opposite().toCoord());
            }
        } else {
            keyPoints.add(headDirection.opposite().toCoord());
        }

        keyPoints.set(0, newHead);

        // Обрабатываем хвост
        if (!ateFood) {
            shrinkTail();
        }

        // Оптимизируем ключевые точки
        optimizeKeyPoints();

        return oldTail;
    }

    private void shrinkTail() {
        if (keyPoints.size() < 2) return;

        Coord lastOffset = keyPoints.get(keyPoints.size() - 1);
        int length = Math.abs(lastOffset.getX()) + Math.abs(lastOffset.getY());

        if (length <= 1) {
            keyPoints.remove(keyPoints.size() - 1);
        } else {
            int dx = lastOffset.getX() == 0 ? 0 : lastOffset.getX() / Math.abs(lastOffset.getX());
            int dy = lastOffset.getY() == 0 ? 0 : lastOffset.getY() / Math.abs(lastOffset.getY());
            keyPoints.set(keyPoints.size() - 1,
                    new Coord(lastOffset.getX() - dx, lastOffset.getY() - dy));
        }
    }

    private void optimizeKeyPoints() {
        // Объединяем последовательные смещения в одном направлении
        for (int i = 1; i < keyPoints.size() - 1; i++) {
            Coord current = keyPoints.get(i);
            Coord next = keyPoints.get(i + 1);

            Direction dirCurrent = getDirectionFromOffset(current);
            Direction dirNext = getDirectionFromOffset(next);

            if (dirCurrent == dirNext) {
                keyPoints.set(i, new Coord(
                        current.getX() + next.getX(),
                        current.getY() + next.getY()
                ));
                keyPoints.remove(i + 1);
                i--;
            }
        }

        // Удаляем нулевые смещения
        keyPoints.removeIf(c -> keyPoints.indexOf(c) > 0 && c.getX() == 0 && c.getY() == 0);
    }

    private Direction getDirectionFromOffset(Coord offset) {
        if (offset.getX() > 0) return Direction.RIGHT;
        if (offset.getX() < 0) return Direction.LEFT;
        if (offset.getY() > 0) return Direction.DOWN;
        return Direction.UP;
    }

    public Snake copy() {
        List<Coord> pointsCopy = new ArrayList<>();
        for (Coord c : keyPoints) {
            pointsCopy.add(c.copy());
        }
        return new Snake(playerId, pointsCopy, state, headDirection);
    }
}