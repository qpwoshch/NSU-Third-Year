package org.example;

import java.io.*;
import java.net.*;


public class SendData {
    private int port;
    private final File file;
    private final InetAddress serverAddress;


    public SendData(String path, String ip, String port) throws SocketException, UnknownHostException {
        this.file = new File(path);
        this.serverAddress = InetAddress.getByName(ip);
        Error error = new Error();
        this.port = error.checkParametrs(port, path);
    }

    public void send() throws IOException {
        try (Socket socket = new Socket(serverAddress, port)) {
            FileInputStream fileInputStream = new FileInputStream(file);
            OutputStream outputStream = socket.getOutputStream();
            System.out.println("Starting file transfer...");
            DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
            dataOutputStream.writeUTF(file.getName());
            dataOutputStream.writeLong(file.length());
            System.out.println("File name and size sent.");
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                dataOutputStream.write(buffer, 0, bytesRead);
                dataOutputStream.flush();
            }
            DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
            String serverMessage = dataInputStream.readUTF();
            System.out.println("Server response: " + serverMessage);
        }

    }
}
