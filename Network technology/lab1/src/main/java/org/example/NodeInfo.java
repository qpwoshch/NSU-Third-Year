package org.example;

public class NodeInfo {
    final String ip;
    volatile long lastSeen;

    NodeInfo(String ip, long lastSeen) {
        this.ip = ip;
        this.lastSeen = lastSeen;
    }
}
