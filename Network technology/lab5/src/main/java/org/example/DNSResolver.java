package org.example;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import org.xbill.DNS.ARecord;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class DNSResolver implements Attachment{
    private DatagramChannel channel;
    private static final List<InetSocketAddress> DNS_SERVERS = List.of(
            new InetSocketAddress("1.1.1.1", 53),     // Cloudflare
            new InetSocketAddress("8.8.8.8", 53),     // Google
            new InetSocketAddress("9.9.9.9", 53)      // Quad9
    );
    private static final InetSocketAddress DNSServer = DNS_SERVERS.get(0);
    private final Map<Integer, DNSObjects> DNSServerAnswer = new HashMap<>();
    private final int BUFSIZE = 512;
    private final Selector selector;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);

    public DNSResolver(Selector selector) throws IOException {
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(DNSServer);
        channel.register(selector, SelectionKey.OP_READ, this);
        this.selector = selector;
    }


    @Override
    public void handle(SelectionKey key) throws Exception {
        read();
    }

    public boolean resolve (String domain, int port, Connect connect, SelectionKey clientKey) {
        if (domain == null || domain.isEmpty() || domain.length() > 253) {
            System.err.println("Invalid domain length or null: '" + domain + "'");
            return false;
        }
        try {
            Name name;
            if (domain.endsWith(".")) {
                name = Name.fromString(domain);
            }
            else {
                name = Name.fromString(domain + ".");
            }
            Record record = Record.newRecord(name, Type.A, DClass.IN);
            Message query = Message.newQuery(record);
            int id = query.getHeader().getID();
            DNSObjects data = new DNSObjects(connect, clientKey, port);
            channel.write(ByteBuffer.wrap(query.toWire()));
            DNSServerAnswer.put(id, data);
            return true;
        } catch (TextParseException e) {
            System.err.println("Invalid domain name: '" + domain + "'");
            return false;
        } catch (IOException e) {
            System.err.println("Failed to send DNS query for '" + domain + "': " + e);
            return false;
        } catch (Exception e) {
            System.err.println("Unexpected DNS error for '" + domain + "': " + e);
            return false;
        }
    }

    public void read() {
        buffer.clear();
        try {
            long bytesRead = channel.read(buffer);
            if (bytesRead == -1) {
                System.err.println("DNS channel closed by remote (EOF). Reconnecting...");
                cleanupAndReconnect();
                return;
            }
            if (bytesRead == 0) {
                return;
            }
            buffer.flip();
            Message response = new Message(buffer);
            int id = response.getHeader().getID();
            DNSObjects data = DNSServerAnswer.remove(id);
            if (data == null) {
                return;
            }
            InetAddress resolvedIp = null;
            for (Record rec : response.getSection(Section.ANSWER)) {
                if (rec.getType() == Type.A) {
                    resolvedIp = ((ARecord) rec).getAddress();
                    break;
                }
            }
            if (resolvedIp != null) {
                data.connect().continueWithIp(resolvedIp, data.port(), data.key());
            } else {
                System.err.println("DNS resolution failed (no A record) for domain requested by client");
                try {
                    data.connect().sendError();
                } catch (Exception e) {
                    System.err.println("Failed to send error to client after DNS failure: " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("DNS read error: " + e);
        }
    }

    private void cleanupAndReconnect() {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (Exception ignored) {}
        }
        for (DNSObjects data : DNSServerAnswer.values()) {
            try {
                data.connect().sendError();
            } catch (Exception ignored) {}
        }
        DNSServerAnswer.clear();
        try {
            channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.connect(DNSServer);
            channel.register(selector, SelectionKey.OP_READ, this);
            System.out.println("DNS resolver reconnected to " + DNSServer);
        } catch (Exception e) {
            System.err.println("Failed to reconnect DNS channel: " + e);
        }
    }
}
