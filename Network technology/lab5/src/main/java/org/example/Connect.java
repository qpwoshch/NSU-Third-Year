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

public class Connect {
    private SocketChannel client;
    private  Selector selector;
    private int MAXSIZE = 512;
    private  ByteBuffer buffer = ByteBuffer.allocate(MAXSIZE);
    private SocketChannel remote = null;
    private final DNSResolver resolver;



    public Connect(SocketChannel client, Selector selector, DNSResolver resolver) {
        this.client = client;
        this.selector = selector;
        this.resolver = resolver;
    }


    //TODO magic constant
    public void connect(SelectionKey key) throws IOException {
        int read = client.read(buffer);
        if (read == -1) {
            client.close();
            return;
        }
        if (read == 0) {
            return;
        }
        if (buffer.position() > MAXSIZE) {
            client.close();
            return;
        }
        buffer.flip();
        if (buffer.remaining() < 10) {
            buffer.compact();
            return;
        }
        buffer.get();
        byte command = buffer.get();
        if (command != 0x01) {
            System.out.println("Invalid command.");
            client.close();
            return;
        }
        buffer.get();
        byte address = buffer.get();
        String destAddress = "";
        int destPort = 0;
        switch (address) {
            case 0x01:
                byte[] ipv4 = new byte[4];
                buffer.get(ipv4);
                destAddress = InetAddress.getByAddress(ipv4).getHostAddress();
                System.out.println("IPv4 address: " + destAddress);
                break;
            case 0x03:
                byte len = buffer.get();
                byte[] bytes = new byte[len];
                buffer.get(bytes);
                String domain = new String(bytes, StandardCharsets.US_ASCII).toLowerCase();
                int domainPort = (buffer.get() & 0xFF) << 8 | (buffer.get() & 0xFF);
                resolver.resolve(domain, domainPort, this, key);
                buffer.clear();
                return;
            default:
                System.out.println("Unsupported address type.");
                client.close();
                return;
        }
        byte firstByte = buffer.get();
        byte secondByte = buffer.get();
        destPort = ((firstByte & 0xFF) << 8) | (secondByte & 0xFF);
        System.out.println("Destination: " + destAddress + ":" + destPort);
        remote = SocketChannel.open();
        remote.configureBlocking(false);
        remote.connect(new InetSocketAddress(destAddress, destPort));
        remote.register(selector, SelectionKey.OP_CONNECT, this);

        key.interestOps(SelectionKey.OP_READ);
        buffer.clear();
    }

    public void finishConnect(SelectionKey remoteKey) throws IOException {
        if (!remote.finishConnect()) {
            client.close();
            remote.close();
            return;
        }

        byte[] response = {0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
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
