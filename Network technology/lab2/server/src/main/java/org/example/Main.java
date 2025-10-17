package org.example;

import java.io.IOException;


public class Main {
    public static void main(String[] args) throws IOException {
        DataReception reception = new DataReception(args[0]);
        reception.startThread();
    }
}