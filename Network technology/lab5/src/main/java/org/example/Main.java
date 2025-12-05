package org.example;

import java.io.IOException;

import static java.lang.Integer.parseInt;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Error: port number is required");
            System.exit(1);
        }
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Error: '" + args[0] + "' is not a valid port number");
            System.exit(1);
            return;
        }
        try {
            new MySelector(port).start();
        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}