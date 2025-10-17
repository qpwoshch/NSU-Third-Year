package org.example;

import java.io.File;

import static java.lang.Integer.parseInt;

public class Error {
    public int checkParametrs(String port, String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.err.println("File does not exist: " + path);
            System.exit(1);
        }
        try {
            return parseInt(port);
        } catch (NumberFormatException e) {
            System.err.println("Non numerical value is specified as a port");
            System.exit(1);
        }
        return 0;
    }
}
