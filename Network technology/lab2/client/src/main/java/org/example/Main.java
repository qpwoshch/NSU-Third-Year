package org.example;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;

public class Main {
    public static void main(String[] args) throws IOException {
        SendData send = new SendData(args[0], args[1], args[2]);
        send.send();
    }
}