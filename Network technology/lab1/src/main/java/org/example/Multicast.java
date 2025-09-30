package org.example;

import java.io.IOException;
import java.net.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Multicast {
    private final String groupAddr;
    private static final int port = 5000;
    private static final String myID = UUID.randomUUID().toString();
    private static final int interval = 3000;
    private String protocol;
    InetAddress group;
    private MulticastSocket socket;

    private final Map<String, NodeInfo> alive = new ConcurrentHashMap<>();



    public Multicast(String addr) {
        this.groupAddr = addr;
    }

    public String getProtocol() {
        if (group instanceof Inet4Address) {
            return "IPv4";
        }
        return "IPv6";
    }

    private void sendConfirmation() {
        try {
            String myIP = InetAddress.getLocalHost().getHostAddress();
            long currentTime = System.currentTimeMillis();
            String message = myID + "|" + myIP + "|" + currentTime;
            byte[] data = message.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("error in sending confirmation" + e);
        }
    }

    private void startSend() {
        new Thread(() -> {
            while (true) {
                sendConfirmation();
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    private void receiveConfirmation() {
        new Thread(() -> {
            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (true) {
                try {
                    socket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength());
                    String[] parts = received.split("\\|");
                    if (parts.length == 3) {
                        String senderId = parts[0];
                        String senderIp = parts[1];
                        if (!senderId.equals(myID)) {
                            boolean isNew = !alive.containsKey(senderId);
                            alive.put(senderId, new NodeInfo(senderIp, System.currentTimeMillis()));
                            if (isNew) {
                                print();
                            }
                        }
                    }

                } catch (IOException e) {
                    break;
                }
            }
        }).start();
    }

    private void print() {
        System.out.println("Live copies:");
        for (Map.Entry<String, NodeInfo> entry : alive.entrySet()) {
            System.out.println("ip: " + entry.getValue().ip + " id: " + entry.getKey());
        }
    }

    private void startClean() {
        new Thread(() -> {
            while (true) {
                long thisMoment = System.currentTimeMillis();
                boolean changed = false;
                for (String id : alive.keySet()) {
                    if (thisMoment - alive.get(id).lastSeen > 2 * interval) {
                        alive.remove(id);
                        changed = true;
                    }
                }
                if (changed) {
                    print();
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }

    public void start() {
        try {
            group = InetAddress.getByName(groupAddr);
            protocol = getProtocol();
            socket = new MulticastSocket(port);
            socket.setReuseAddress(true);
            socket.setTimeToLive(1);
            socket.setLoopbackMode(false);
            NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            socket.joinGroup(new InetSocketAddress(group, port), ni);
            receiveConfirmation();
            startSend();
            startClean();
        } catch (UnknownHostException e) {
            System.err.println(groupAddr + " Incorrect address");
        } catch (IOException e) {
            System.err.println("error: " + e);
        }
    }
}
