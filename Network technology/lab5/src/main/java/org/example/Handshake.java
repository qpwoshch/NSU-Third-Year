package org.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class Handshake implements Attachment {
    private final int BUFSIZE = 512;
    private ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
    private final SocketChannel client;
    private final DNSResolver resolver;
    private final byte VERSION = 0x05;
    private final byte NO_AUTH = 0x00;
    private static final byte NO_ACCEPTABLE_METHODS = (byte) 0xFF;
    private final byte[] SUCCESS = {VERSION, NO_AUTH};

    public Handshake(SocketChannel client, DNSResolver resolver) {
        this.client = client;
        this.resolver = resolver;
    }

    @Override
    public void handle(SelectionKey key) throws Exception {
        doHandshake(key);
    }

    public void doHandshake(SelectionKey key) throws IOException {
        int read = client.read(buffer);
        if (read == -1) {
            key.cancel();
            client.close();
            return;
        }
        if (read == 0) {
            return;
        }
        buffer.flip();
        if (buffer.remaining() < 3) {
            buffer.compact();
            return;
        }
        byte version = buffer.get();
        if (version != 0x05) {
            sendReplyAndClose(key, NO_ACCEPTABLE_METHODS);
            return;
        }
        int nmethods = buffer.get();
        if (buffer.remaining() < nmethods) {
            buffer.compact();
            return;
        }

        boolean hasNoAuth = false;
        int methodsRead = 0;
        while (methodsRead < nmethods) {
            if (buffer.get() == 0x00) {
                hasNoAuth = true;
            }
            methodsRead++;
        }

        if (!hasNoAuth) {
            sendReplyAndClose(key, NO_ACCEPTABLE_METHODS);
            return;
        }


        if (client.write(ByteBuffer.wrap(SUCCESS)) <= 0) {
            closeConnection(key);
        }

        key.attach(new Connect(client, key.selector(), resolver));
        buffer.clear();
    }

    private void sendReplyAndClose(SelectionKey key, byte method) {
        try {
            client.write(ByteBuffer.wrap(new byte[]{VERSION, method}));
        } catch (Exception ignored) {}
        closeConnection(key);
    }

    private void closeConnection(SelectionKey key) {
        key.cancel();
        try {
            client.close();
        } catch (IOException ignored) {}
    }
}