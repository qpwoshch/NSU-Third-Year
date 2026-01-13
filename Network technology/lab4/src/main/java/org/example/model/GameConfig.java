package org.example.model;

public class GameConfig {
    private final int width;
    private final int height;
    private final int foodStatic;
    private final int stateDelayMs;

    public GameConfig(int width, int height, int foodStatic, int stateDelayMs) {
        this.width = Math.max(10, Math.min(100, width));
        this.height = Math.max(10, Math.min(100, height));
        this.foodStatic = Math.max(0, Math.min(100, foodStatic));
        this.stateDelayMs = Math.max(100, Math.min(3000, stateDelayMs));
    }

    public static GameConfig defaultConfig() {
        return new GameConfig(40, 30, 1, 1000);
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getFoodStatic() { return foodStatic; }
    public int getStateDelayMs() { return stateDelayMs; }
}