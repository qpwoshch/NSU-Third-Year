package org.example;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static java.lang.Math.pow;


public class DataReception {
    private final ServerSocket serverSocket;
    private long fileSize;
    String fileName;
    private long startTime;

    public DataReception(String port) throws IOException {
        Error error = new Error();
        serverSocket  = new ServerSocket(error.checkPort(port));
    }

    public void startThread() throws IOException {
        while (true) {
            try  {
                Socket socket = serverSocket.accept();
                System.out.println("Client connected: " + socket.getInetAddress());
                startTime = System.currentTimeMillis();
                MonitorSpeed speedMonitor = new MonitorSpeed(startTime);
                Thread speedMonitorThread = new Thread(speedMonitor);
                speedMonitorThread.start();
                new Thread(() -> {
                    try {
                        receive(socket, speedMonitor, speedMonitorThread);
                    } catch (IOException e) {
                        System.err.println("Error handling client: " + e.getMessage());
                    }
                }).start();
            } catch (IOException e) {
                System.err.println("Error accepting client connection: " + e.getMessage());
            }
        }
    }

    public void receive(Socket socket, MonitorSpeed speedMonitor, Thread speedMonitorThread) throws IOException {
        InputStream inputStream = null;
        DataInputStream dataInputStream = null;
        FileOutputStream fileOutputStream = null;
        try  {
            inputStream = socket.getInputStream();
            dataInputStream = new DataInputStream(inputStream);
            fileName = dataInputStream.readUTF();
            System.out.println("Received file name: " + fileName);
            fileSize = dataInputStream.readLong();
            if (fileSize > 1.1 * pow(10, 12)) {
                System.err.println("File size exceeds acceptable limit (" + fileSize + " bytes)");
                sendError(socket, "Error: file size exceeds 1 TB limit. Transfer aborted.");
                throw new IOException("File too large");
            }
            System.out.println("File size: " + fileSize / 1024 / 1024 + " mb");
            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) {
                uploadsDir.mkdir();
            }
            File outputFile = new File(uploadsDir, fileName);
            fileOutputStream = new FileOutputStream(outputFile);
            byte[] buffer = new byte[1024];
            long remaining = fileSize;
            int bytesRead;
            while (remaining > 0) {
                bytesRead = dataInputStream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (bytesRead == -1) {
                    break;
                }
                fileOutputStream.write(buffer, 0, bytesRead);
                remaining -= bytesRead;
                speedMonitor.updateReceivedBytes(bytesRead, fileName);
            }
            sendConfirmation(socket, transferIsSuccessful(outputFile));
        } catch (IOException e) {
            System.err.println("Error during file reception: " + e.getMessage());
            throw e;
        } finally {
            try {
                if (fileOutputStream != null) fileOutputStream.close();
                if (dataInputStream != null) dataInputStream.close();
                if (inputStream != null) inputStream.close();
            } catch (IOException e) {
                System.err.println("Error closing resources: " + e.getMessage());
            }
            speedMonitor.stopMonitoring();
            speedMonitorThread.interrupt();
            closeClientConnection(socket, speedMonitor, speedMonitorThread);
        }

    }


    private void sendError(Socket socket, String message) {
        try (DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream())) {
            dataOutputStream.writeUTF(message);
        } catch (IOException ignored) {}
    }

    private void closeClientConnection(Socket socket, MonitorSpeed speedMonitor, Thread monitorThread) {
        try {
            speedMonitor.stopMonitoring();
            if (monitorThread != null && monitorThread.isAlive()) monitorThread.interrupt();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("Client connection closed.");
        } catch (IOException e) {
            System.err.println("Error closing client connection: " + e.getMessage());
        }
    }

    private boolean transferIsSuccessful(File outputFile) {
        if (outputFile.length() == fileSize) {
            return true;
        }
        return false;
    }

    private void sendConfirmation(Socket socket, boolean success) throws IOException {
        OutputStream outputStream = socket.getOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        if (success) {
            dataOutputStream.writeUTF("File " + fileName + " received successfully.");
        } else {
            dataOutputStream.writeUTF("File " + fileName + " transfer failed.");
        }
    }
}
