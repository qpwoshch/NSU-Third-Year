package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.example.MySelector.TRANSFER_BUFFER;

public class Proxy {
    private final SocketChannel client;
    private final SocketChannel remote;

    public Proxy(SocketChannel client, SocketChannel remote) {
        this.client = client;
        this.remote = remote;
    }

    public void transfer(SelectionKey key) throws IOException {
        SocketChannel from = (SocketChannel) key.channel();
        SocketChannel to;
        if (from == client) {
            to = remote;
        }
        else {
            to = client;
        }
        ByteBuffer buffer = TRANSFER_BUFFER.get();
        buffer.clear();
        int read = from.read(buffer);
        if (read == -1) {
            client.close();
            remote.close();
            return;
        }
        if (read > 0) {
            buffer.flip();
            to.write(buffer);
        }
    }
}