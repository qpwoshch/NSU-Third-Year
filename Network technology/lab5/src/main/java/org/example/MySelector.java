package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;


public class MySelector {
    private int port;
    public static final ThreadLocal<ByteBuffer> TRANSFER_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(32768));
    private DNSResolver resolver;
    private volatile boolean isRun = true;
    private Selector selector;

    public MySelector(Integer port) {
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress("localhost", port));
        serverSocketChannel.configureBlocking(false);
        selector = Selector.open();
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        resolver = new DNSResolver(selector);
        while (isRun) {
            selector.select();
            if (!isRun) {
                break;
            }
            for (SelectionKey key : selector.selectedKeys()) {
                try {
                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isAcceptable()) {
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel client = server.accept();
                        if (client == null) {
                            continue;
                        }
                        client.configureBlocking(false);
                        client.register(selector, SelectionKey.OP_READ, new Handshake(client, resolver));
                        System.out.println("Client: " + client.getRemoteAddress());
                    }
                    if (key.isConnectable()) {
                        ((Connect) key.attachment()).finishConnect(key);
                    }
                    if (key.isReadable()) {
                        Object att = key.attachment();
                        if (att instanceof Attachment handler) {
                            handler.handle(key);
                            continue;
                        }
                    }
                } catch (Exception e) {
                    try {
                        key.channel().close();
                    } catch (Exception ignored) {}
                    key.cancel();
                }
            }
            selector.selectedKeys().clear();
        }
        cleanup();
    }

    public void stop() {
        isRun = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void cleanup() {
        if (selector != null) {
            try {
                for (SelectionKey key : selector.keys()) {
                    if (key.channel() != null) {
                        key.channel().close();
                    }
                    key.cancel();
                }
                selector.close();
            } catch (Exception ignored) {}
        }
    }


}
