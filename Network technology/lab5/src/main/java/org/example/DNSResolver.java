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
import java.util.Map;


public class DNSResolver {
    private DatagramChannel channel;
    private static final InetSocketAddress DNSServer = new InetSocketAddress("1.1.1.1", 53);
    private final Map<Integer, Object[]> DNSServerAnswer = new HashMap<>();
    public int BUFSIZE = 512;

    public DNSResolver(Selector selector) throws IOException {
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.connect(DNSServer);
        channel.register(selector, SelectionKey.OP_READ, this);
    }




    public void resolve (String domain, int port, Connect connect, SelectionKey clientKey) {
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
            DNSServerAnswer.put(id, new Object[]{connect, clientKey, port});
            channel.write(ByteBuffer.wrap(query.toWire()));
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    public void read() {
        ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
        try {
            channel.read(buffer);
            buffer.flip();
            Message response = new Message(buffer);
            int id = response.getHeader().getID();
            Object[] data = DNSServerAnswer.remove(id);
            Connect connect = (Connect)data[0];
            SelectionKey key = (SelectionKey)data[1];
            int port = (int)data[2];
            Record[] answers = response.getSectionArray(Section.ANSWER);
            if (answers.length == 0) {
                return;
            }
            InetAddress resolvedIp = null;
            for (Record rec : answers) {
                if (rec.getType() == Type.A) {
                    resolvedIp = ((ARecord) rec).getAddress();
                    break;
                }
            }
            if (resolvedIp == null) {
                return;
            }
            connect.continueWithIp(resolvedIp, port, key);
        } catch (Exception e) {
            System.err.println(e);
        }
    }
}
