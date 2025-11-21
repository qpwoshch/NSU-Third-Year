package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class Handshake {
    private ByteBuffer buffer = ByteBuffer.allocate(256);
    private final SocketChannel client;
    private final DNSResolver resolver;

    public Handshake(SocketChannel client, DNSResolver resolver) {
        this.client = client;
        this.resolver = resolver;
    }

    public void doHandshake(SelectionKey key) throws IOException {
        client.read(buffer);
        buffer.flip();
        if (buffer.remaining() < 3) {
            buffer.compact();
            return;
        }
        buffer.get();
        int nmethods = buffer.get();
        if (buffer.remaining() < nmethods) {
            buffer.compact();
            return;
        }
        buffer.position(buffer.position() +nmethods);

        byte[] response = {0x05, 0x00};
        client.write(ByteBuffer.wrap(response));

        key.attach(new Connect(client, key.selector(), resolver));
        buffer.clear();
    }
}