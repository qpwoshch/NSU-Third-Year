package org.example;

import static java.lang.Integer.parseInt;

public class Error {
    public int checkPort(String port) {
        try {
            return parseInt(port);
        } catch (NumberFormatException e) {
            System.err.println("Non numerical value is specified as a port");
            System.exit(1);
        }
        return 0;
    }
}
