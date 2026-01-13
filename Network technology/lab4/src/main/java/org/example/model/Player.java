package org.example.model;

import org.example.game.NodeRole;
import java.net.InetSocketAddress;

public class Player {
    private final int id;
    private String name;
    private InetSocketAddress address;
    private NodeRole role;
    private int score;
    private long lastActivity;

    public Player(int id, String name, NodeRole role) {
        this.id = id;
        this.name = name;
        this.role = role;
        this.score = 0;
        this.lastActivity = System.currentTimeMillis();
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public InetSocketAddress getAddress() { return address; }
    public void setAddress(InetSocketAddress address) { this.address = address; }
    public NodeRole getRole() { return role; }
    public void setRole(NodeRole role) { this.role = role; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public void addScore(int delta) { this.score += delta; }
    public long getLastActivity() { return lastActivity; }
    public void updateActivity() { this.lastActivity = System.currentTimeMillis(); }
}