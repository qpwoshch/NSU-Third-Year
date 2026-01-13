package org.example.network;

import org.example.SnakesProto;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public class NetworkManager {

    private DatagramSocket unicastSocket;
    private MulticastSocket multicastSocket;
    private final BiConsumer<SnakesProto.GameMessage, InetSocketAddress> messageHandler;
    private final ExecutorService executor;
    private volatile boolean running;

    private InetAddress multicastGroup;
    private int multicastPort;

    public NetworkManager(BiConsumer<SnakesProto.GameMessage, InetSocketAddress> messageHandler) {
        this.messageHandler = messageHandler;
        this.executor = Executors.newFixedThreadPool(3);
    }

    public void start() {
        try {
            unicastSocket = new DatagramSocket();
            unicastSocket.setBroadcast(true);
            running = true;

            executor.submit(this::receiveLoop);

            System.out.println("[NET] Started on port: " + unicastSocket.getLocalPort());
        } catch (SocketException e) {
            throw new RuntimeException("Failed to start network manager", e);
        }
    }

    public void stop() {
        running = false;

        if (unicastSocket != null && !unicastSocket.isClosed()) {
            unicastSocket.close();
        }

        stopMulticastReceiver();
        executor.shutdownNow();
    }

    public void startMulticastReceiver(String address, int port) {
        try {
            this.multicastGroup = InetAddress.getByName(address);
            this.multicastPort = port;

            stopMulticastReceiver();

            multicastSocket = new MulticastSocket(null);
            multicastSocket.setReuseAddress(true);
            multicastSocket.bind(new InetSocketAddress(port));

            List<NetworkInterface> interfaces = getMulticastInterfaces();
            System.out.println("[NET] Found " + interfaces.size() + " multicast interfaces");

            boolean joined = false;
            for (NetworkInterface ni : interfaces) {
                try {
                    multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, port), ni);
                    System.out.println("[NET] Joined multicast on: " + ni.getDisplayName());
                    joined = true;
                } catch (Exception e) {
                    System.err.println("[NET] Failed to join on " + ni.getDisplayName() + ": " + e.getMessage());
                }
            }

            if (!joined) {
                try {
                    multicastSocket.joinGroup(multicastGroup);
                    System.out.println("[NET] Joined multicast on default interface");
                } catch (Exception e) {
                    System.err.println("[NET] Failed to join multicast: " + e.getMessage());
                }
            }

            executor.submit(this::multicastReceiveLoop);

            System.out.println("[NET] Multicast receiver started on " + address + ":" + port);
        } catch (IOException e) {
            System.err.println("[NET] Failed to start multicast receiver: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<NetworkInterface> getMulticastInterfaces() {
        List<NetworkInterface> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (ni.isUp() && ni.supportsMulticast()) {
                        Enumeration<InetAddress> addresses = ni.getInetAddresses();
                        while (addresses.hasMoreElements()) {
                            InetAddress addr = addresses.nextElement();
                            if (addr instanceof Inet4Address) {
                                result.add(ni);
                                System.out.println("[NET] Interface: " + ni.getDisplayName() +
                                        " (" + addr.getHostAddress() + ")");
                                break;
                            }
                        }
                    }
                } catch (SocketException ignored) {
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return result;
    }

    public void stopMulticastReceiver() {
        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                multicastSocket.leaveGroup(multicastGroup);
            } catch (Exception ignored) {
            }
            multicastSocket.close();
            multicastSocket = null;
        }
    }

    public void send(SnakesProto.GameMessage message, InetSocketAddress address) {
        if (unicastSocket == null || unicastSocket.isClosed() || address == null) return;

        try {
            byte[] data = message.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send to " + address + ": " + e.getMessage());
        }
    }

    public void sendMulticast(SnakesProto.GameMessage message, String address, int port) {
        if (unicastSocket == null || unicastSocket.isClosed()) return;

        try {
            byte[] data = message.toByteArray();
            InetAddress group = InetAddress.getByName(address);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
            unicastSocket.send(packet);
            System.out.println("[NET] Sent multicast to " + address + ":" + port);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send multicast: " + e.getMessage());
        }
    }

    public void sendBroadcast(SnakesProto.GameMessage message, int port) {
        if (unicastSocket == null || unicastSocket.isClosed()) return;

        try {
            byte[] data = message.toByteArray();
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
            unicastSocket.send(packet);
            System.out.println("[NET] Sent broadcast to port " + port);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send broadcast: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                unicastSocket.setSoTimeout(500);
                unicastSocket.receive(packet);

                processPacket(packet, "UNICAST");
            } catch (SocketTimeoutException e) {
                // OK
            } catch (SocketException e) {
                if (running) {
                    System.err.println("[NET] Socket error: " + e.getMessage());
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[NET] IO error: " + e.getMessage());
                }
            }
        }
    }

    private void multicastReceiveLoop() {
        byte[] buffer = new byte[65535];
        System.out.println("[NET] Multicast receive loop started");

        while (running && multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.setSoTimeout(500);
                multicastSocket.receive(packet);

                processPacket(packet, "MULTICAST");
            } catch (SocketTimeoutException e) {
                // OK
            } catch (SocketException e) {
                if (running) {
                    System.err.println("[NET] Multicast socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[NET] Multicast IO error: " + e.getMessage());
                }
            }
        }

        System.out.println("[NET] Multicast receive loop ended");
    }

    private void processPacket(DatagramPacket packet, String source) {
        try {
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

            SnakesProto.GameMessage message = SnakesProto.GameMessage.parseFrom(data);
            InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());

            System.out.println("[NET] " + source + " from " + sender + " type: " + getMessageType(message));

            messageHandler.accept(message, sender);
        } catch (Exception e) {
            System.err.println("[NET] Failed to process packet: " + e.getMessage());
        }
    }

    private String getMessageType(SnakesProto.GameMessage msg) {
        if (msg.hasAck()) return "ACK";
        if (msg.hasAnnouncement()) return "ANNOUNCEMENT";
        if (msg.hasDiscover()) return "DISCOVER";
        if (msg.hasJoin()) return "JOIN";
        if (msg.hasState()) return "STATE";
        if (msg.hasSteer()) return "STEER";
        if (msg.hasPing()) return "PING";
        if (msg.hasRoleChange()) return "ROLE_CHANGE";
        if (msg.hasError()) return "ERROR";
        return "UNKNOWN";
    }

    public int getLocalPort() {
        return unicastSocket != null ? unicastSocket.getLocalPort() : 0;
    }
}