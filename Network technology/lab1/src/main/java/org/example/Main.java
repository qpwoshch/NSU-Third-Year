package org.example;

public class Main {
    public static void main(String[] args) {
        Multicast m = new Multicast(args[0]);
        m.start();
    }
}