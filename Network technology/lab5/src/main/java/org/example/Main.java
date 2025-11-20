package org.example;

import java.io.IOException;

import static java.lang.Integer.parseInt;

public class Main {
    public static void main(String[] args) throws IOException {
        new MySelector(parseInt(args[0])).start();
    }
}