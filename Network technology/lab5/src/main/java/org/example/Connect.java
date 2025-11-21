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
    private final int MASK = 0xFF;
    private final byte CON = 0x01;
    private final byte IPV4 = 0x01;
    private final byte DNS = 0x03;
    private final byte VER = 0x05;
    private final byte REP = 0x00;
    private final byte RSV = 0x00;
    private final byte BINDADDR = 0x00;
    private final byte BINDPORT = 0x00;
    private final int IPV4SIZE = 4;



    public Connect(SocketChannel client, Selector selector, DNSResolver resolver) {
        this.client = client;
        this.selector = selector;
        this.resolver = resolver;
    }


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
        if (command != CON) {
            System.out.println("Invalid command.");
            client.close();
            return;
        }
        buffer.get();
        byte address = buffer.get();
        String destAddress = "";
        int destPort = 0;
        switch (address) {
            case IPV4:
                byte[] IPV4 = new byte[IPV4SIZE];
                buffer.get(IPV4);
                destAddress = InetAddress.getByAddress(IPV4).getHostAddress();
                System.out.println("IPv4 address: " + destAddress);
                break;
            case DNS:
                byte len = buffer.get();
                byte[] bytes = new byte[len];
                buffer.get(bytes);
                String domain = new String(bytes, StandardCharsets.US_ASCII).toLowerCase();
                int domainPort = (buffer.get() & MASK) << 8 | (buffer.get() & MASK);
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
        destPort = ((firstByte & MASK) << 8) | (secondByte & MASK);
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
