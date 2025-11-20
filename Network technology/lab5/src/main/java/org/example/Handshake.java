package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class Handshake {
    private ByteBuffer buffer = ByteBuffer.allocate(256);
    private final SocketChannel client;

    public Handshake(SocketChannel client) {
        this.client = client;
    }

    public void doHandshake(SelectionKey key) throws IOException {
        client.read(buffer);
        buffer.flip();

        buffer.get();
        int countMethods = buffer.get();
        buffer.position(buffer.position() +countMethods);

        byte[] response = {0x05, 0x00};
        client.write(ByteBuffer.wrap(response));

        key.attach(new Connect(client, key.selector()));
        buffer.clear();
    }
}