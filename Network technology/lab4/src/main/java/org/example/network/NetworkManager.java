package org.example.network;

import org.example.SnakesProto;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class NetworkManager {

    private volatile DatagramSocket unicastSocket;
    private volatile MulticastSocket multicastSocket;
    private final BiConsumer<SnakesProto.GameMessage, InetSocketAddress> messageHandler;
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private InetAddress multicastGroup;
    private int multicastPort;

    public NetworkManager(BiConsumer<SnakesProto.GameMessage, InetSocketAddress> messageHandler) {
        this.messageHandler = messageHandler;
    }

    public boolean isRunning() {
        return running.get() && unicastSocket != null && !unicastSocket.isClosed();
    }

    public void start() {
        if (isRunning()) {
            System.out.println("[NET] Already running on port " + unicastSocket.getLocalPort());
            return;
        }

        try {
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newFixedThreadPool(3);
            }

            unicastSocket = new DatagramSocket();
            unicastSocket.setBroadcast(true);
            running.set(true);

            executor.submit(this::receiveLoop);

            System.out.println("[NET] Started on port: " + unicastSocket.getLocalPort());
        } catch (SocketException e) {
            throw new RuntimeException("Failed to start network manager", e);
        }
    }

    public void restart() {
        System.out.println("[NET] Restarting...");

        // Закрываем текущий unicast сокет
        DatagramSocket oldSocket = unicastSocket;
        unicastSocket = null;

        if (oldSocket != null && !oldSocket.isClosed()) {
            oldSocket.close();
        }

        stopMulticastReceiver();

        // Небольшая пауза
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}

        // Создаём новый сокет
        if (running.get()) {
            try {
                unicastSocket = new DatagramSocket();
                unicastSocket.setBroadcast(true);

                if (executor == null || executor.isShutdown()) {
                    executor = Executors.newFixedThreadPool(3);
                }

                executor.submit(this::receiveLoop);

                System.out.println("[NET] Restarted on new port: " + unicastSocket.getLocalPort());
            } catch (SocketException e) {
                System.err.println("[NET] Failed to restart: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running.set(false);

        if (unicastSocket != null && !unicastSocket.isClosed()) {
            unicastSocket.close();
            unicastSocket = null;
        }

        stopMulticastReceiver();

        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
        }

        System.out.println("[NET] Stopped");
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

            if (executor != null && !executor.isShutdown()) {
                executor.submit(this::multicastReceiveLoop);
            }

            System.out.println("[NET] Multicast receiver started on " + address + ":" + port);
        } catch (IOException e) {
            System.err.println("[NET] Failed to start multicast receiver: " + e.getMessage());
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
                                break;
                            }
                        }
                    }
                } catch (SocketException ignored) {}
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return result;
    }

    public void stopMulticastReceiver() {
        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                if (multicastGroup != null) {
                    multicastSocket.leaveGroup(multicastGroup);
                }
            } catch (Exception ignored) {}
            multicastSocket.close();
            multicastSocket = null;
        }
    }

    public void send(SnakesProto.GameMessage message, InetSocketAddress address) {
        DatagramSocket socket = unicastSocket;
        if (socket == null || socket.isClosed() || address == null) return;

        try {
            byte[] data = message.toByteArray();
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send to " + address + ": " + e.getMessage());
        }
    }

    public void sendMulticast(SnakesProto.GameMessage message, String address, int port) {
        DatagramSocket socket = unicastSocket;
        if (socket == null || socket.isClosed()) return;

        try {
            byte[] data = message.toByteArray();
            InetAddress group = InetAddress.getByName(address);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send multicast: " + e.getMessage());
        }
    }

    public void sendBroadcast(SnakesProto.GameMessage message, int port) {
        DatagramSocket socket = unicastSocket;
        if (socket == null || socket.isClosed()) return;

        try {
            byte[] data = message.toByteArray();
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[NET] Failed to send broadcast: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        System.out.println("[NET] Unicast receive loop started");

        while (running.get()) {
            DatagramSocket socket = unicastSocket;
            if (socket == null || socket.isClosed()) {
                System.out.println("[NET] Unicast socket closed, exiting loop");
                break;
            }

            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(500);
                socket.receive(packet);

                processPacket(packet);
            } catch (SocketTimeoutException e) {
                // OK - проверяем running
            } catch (SocketException e) {
                if (running.get() && unicastSocket == socket) {
                    System.err.println("[NET] Socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[NET] IO error: " + e.getMessage());
                }
            }
        }
        System.out.println("[NET] Unicast receive loop ended");
    }

    private void multicastReceiveLoop() {
        byte[] buffer = new byte[65535];
        System.out.println("[NET] Multicast receive loop started");

        while (running.get()) {
            MulticastSocket socket = multicastSocket;
            if (socket == null || socket.isClosed()) break;

            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(500);
                socket.receive(packet);

                processPacket(packet);
            } catch (SocketTimeoutException e) {
                // OK
            } catch (SocketException e) {
                if (running.get() && multicastSocket == socket) {
                    System.err.println("[NET] Multicast socket error: " + e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[NET] Multicast IO error: " + e.getMessage());
                }
            }
        }

        System.out.println("[NET] Multicast receive loop ended");
    }

    private void processPacket(DatagramPacket packet) {
        try {
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

            SnakesProto.GameMessage message = SnakesProto.GameMessage.parseFrom(data);
            InetSocketAddress sender = new InetSocketAddress(packet.getAddress(), packet.getPort());

            messageHandler.accept(message, sender);
        } catch (Exception e) {
            System.err.println("[NET] Failed to process packet: " + e.getMessage());
        }
    }

    public int getLocalPort() {
        DatagramSocket socket = unicastSocket;
        return socket != null ? socket.getLocalPort() : 0;
    }
}