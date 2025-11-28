package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.example.MySelector.TRANSFER_BUFFER;

public class Proxy implements Attachment {
    private final SocketChannel client;
    private final SocketChannel remote;

    public Proxy(SocketChannel client, SocketChannel remote) {
        this.client = client;
        this.remote = remote;
    }

    @Override
    public void handle(SelectionKey key) throws Exception {
        transfer(key);
    }

    public void transfer(SelectionKey key) throws IOException {
        SocketChannel from = (SocketChannel) key.channel();
        SocketChannel to;
        // Direction: client → remote or remote → client — determined by which channel triggered the readable event
        if (from == client) {
            to = remote;
        }
        else {
            to = client;
        }
        ByteBuffer buffer = TRANSFER_BUFFER.get();
        if (!buffer.hasRemaining()) {
            buffer.clear();
        }
        int read = from.read(buffer);
        if (read == -1) {
            client.close();
            remote.close();
            return;
        }
        if (read > 0) {
            buffer.flip();
            int written = to.write(buffer);
            if (buffer.hasRemaining()) {
                SelectionKey toKey = to.keyFor(key.selector());
                toKey.interestOps(toKey.interestOps() | SelectionKey.OP_WRITE);
            }
            else {
                buffer.clear();
            }
        }
    }
}