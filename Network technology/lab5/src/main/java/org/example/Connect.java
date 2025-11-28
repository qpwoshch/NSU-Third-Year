package org.example;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.channels.SelectionKey;

public class Connect implements Attachment {
    private final SocketChannel client;
    private final Selector selector;
    private final int MAXSIZE = 512;
    private  ByteBuffer buffer = ByteBuffer.allocate(MAXSIZE);
    private SocketChannel remote = null;
    private final DNSResolver resolver;
    private final int MASK = 0xFF;
    private final byte CON = 0x01;
    private final byte IPV4 = 0x01;
    private final byte IPV6 = 0x04;
    private final byte DNS = 0x03;
    private final byte VER = 0x05;
    private final byte REP_GENERAL_FAILURE = 0x01;
    private final byte REP = 0x00;
    private final byte RSV = 0x00;
    private final byte BINDADDR = 0x00;
    private final byte BINDPORT = 0x00;
    private final int IPV4SIZE = 4;
    private final int IPV6SIZE = 16;



    public Connect(SocketChannel client, Selector selector, DNSResolver resolver) {
        this.client = client;
        this.selector = selector;
        this.resolver = resolver;
    }

    @Override
    public void handle(SelectionKey key) throws Exception {
        connect(key);
    }


    public void connect(SelectionKey key) throws IOException {
        int read = client.read(buffer);
        if (read == -1) {
            System.out.println("[Handshake] Client " + client.getRemoteAddress() + " closed connection (EOF)");
            client.close();
            return;
        }
        if (read == 0) {
            return;
        }
        if (buffer.position() > MAXSIZE) {
            System.out.println("[ERROR] Client " + client.getRemoteAddress() + " exceeded maximum request size (" + buffer.position() + " > " + MAXSIZE + ")");
            sendError();
            return;
        }
        buffer.flip();
        if (buffer.remaining() < 10) {
            buffer.compact();
            return;
        }
        byte version = buffer.get();
        if (version != VER) {
            System.err.println("Invalid version");
            client.close();
            return;
        }
        byte command = buffer.get();
        if (command != CON) {
            System.err.println("Invalid command.");
            client.close();
            return;
        }
        buffer.get();
        byte address = buffer.get();
        String destAddress = "";
        int destPort = 0;
        switch (address) {
            case IPV4:
                byte[] ipv4 = new byte[IPV4SIZE];
                buffer.get(ipv4);
                destAddress = InetAddress.getByAddress(ipv4).getHostAddress();
                System.out.println("IPv4 address: " + destAddress);
                break;
            case IPV6:
                byte[] ipv6 = new byte[IPV6SIZE];
                buffer.get(ipv6);
                destAddress = InetAddress.getByAddress(ipv6).getHostAddress();
                System.out.println("IPv6 address: " + destAddress);
                break;
            case DNS:
                if (buffer.remaining() < 1) {
                    buffer.position(buffer.position() - 4);
                    buffer.compact();
                    return;
                }
                byte len = buffer.get();
                if (len == 0 || len > 255) {
                    System.err.println("Invalid domain length: " + len);
                    sendError();
                    return;
                }
                byte[] bytes = new byte[len];
                buffer.get(bytes);
                String domain = new String(bytes, StandardCharsets.US_ASCII).toLowerCase();
                int domainPort = (buffer.get() & MASK) << 8 | (buffer.get() & MASK);
                if (!resolver.resolve(domain, domainPort, this, key)) {
                    sendError();
                    System.err.println("Failed to send DNS query for: " + domain);
                    buffer.clear();
                    return;
                }
                buffer.clear();
                return;
            default:
                System.out.println("Unsupported address type.");
                client.close();
                return;
        }
        byte firstByte = buffer.get();
        byte secondByte = buffer.get();
        destPort = ((firstByte & MASK) << 8) | (secondByte & MASK);
        if (destPort == 0) {
            System.err.println("Invalid port: 0 (client: " + client.getRemoteAddress() + ")");
            sendError();
            return;
        }
        System.out.println("Destination: " + destAddress + ":" + destPort);
        remote = SocketChannel.open();
        remote.configureBlocking(false);
        remote.connect(new InetSocketAddress(destAddress, destPort));
        boolean connectedImmediately = !remote.isConnectionPending();
        if (connectedImmediately) {
            finishConnect(remote.register(selector, 0, this));
        } else {
            remote.register(selector, SelectionKey.OP_CONNECT, this);
        }
        key.interestOps(SelectionKey.OP_READ);
        buffer.clear();
    }


    public void sendError() throws IOException {
        byte[] errorResponse = {VER, REP_GENERAL_FAILURE, RSV, IPV4, BINDADDR, BINDADDR, BINDADDR, BINDADDR, BINDPORT, BINDPORT};
        try {
            client.write(ByteBuffer.wrap(errorResponse));
        } catch (IOException ignored) {}
        client.close();
    }

    public void finishConnect(SelectionKey remoteKey) throws IOException {
        try {
            if (!remote.finishConnect()) {
                System.err.println("finishConnect() returned false (should not happen)");
                sendError();
                return;
            }
        } catch (Exception e) {
            System.err.println("Connect failed to " + remote.socket().getInetAddress() + ":" + remote.socket().getPort() + " — " + e);
            sendError();
            return;
        }

        byte[] response = {VER, REP, RSV, IPV4, BINDADDR, BINDADDR, BINDADDR, BINDADDR, BINDPORT, BINDPORT};
        client.write(ByteBuffer.wrap(response));

        Proxy proxy = new Proxy(client, remote);
        client.register(selector, SelectionKey.OP_READ, proxy);
        remote.register(selector, SelectionKey.OP_READ, proxy);

        System.out.println("Tunnel is open");
    }

    public void continueWithIp(InetAddress ip, int port, SelectionKey clientKey) throws IOException {
        this.remote = SocketChannel.open();
        this.remote.configureBlocking(false);
        this.remote.connect(new InetSocketAddress(ip, port));
        this.remote.register(selector, SelectionKey.OP_CONNECT, this);
    }
}
