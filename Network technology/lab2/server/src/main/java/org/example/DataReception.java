package org.example;

import java.net.DatagramSocket;
import java.net.SocketException;

import static java.lang.Integer.parseInt;

public class Connect {
    private Error error = new Error();
    private DatagramSocket socket;

    public Connect(String port) throws SocketException {
        socket  = new DatagramSocket(error.checkPort(port));
    }
}
