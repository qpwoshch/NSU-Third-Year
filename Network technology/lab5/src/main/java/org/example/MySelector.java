    package org.example;

    import java.io.IOException;
    import java.net.InetSocketAddress;
    import java.nio.ByteBuffer;
    import java.nio.channels.SelectionKey;
    import java.nio.channels.Selector;
    import java.nio.channels.ServerSocketChannel;
    import java.nio.channels.SocketChannel;


    public class MySelector {
        private Integer port;
        public static final ThreadLocal<ByteBuffer> TRANSFER_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(32768));
        private DNSResolver resolver;

        public MySelector(Integer port) {
            this.port = port;
        }

        public void start() throws IOException {
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress("localhost", port));
            serverSocketChannel.configureBlocking(false);
            Selector selector = Selector.open();
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            resolver = new DNSResolver(selector);
            while (true) {
                selector.select();
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
                            if (att instanceof DNSResolver dns) {
                                dns.read();
                                continue;
                            }
                            if (att instanceof Handshake handshake) {
                                handshake.doHandshake(key);
                            }
                            else if (att instanceof Connect connect) {
                                connect.connect(key);
                            }
                            else if (att instanceof Proxy proxy) {
                                proxy.transfer(key);
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
        }
    }
