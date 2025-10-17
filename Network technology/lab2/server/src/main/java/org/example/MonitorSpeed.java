package org.example;

public class MonitorSpeed implements Runnable{
    private long startTime;
    private boolean isRunning = true;
    private long allBytes;
    private String name;

    public MonitorSpeed(long startTime) {
        this.startTime = startTime;
    }

    public synchronized void updateReceivedBytes(long bytes, String name) {
        this.name = name;
        this.allBytes += bytes;
    }

    @Override
    public void run() {
        long bytesAtTheTimeLastIteration = 0;
        while (isRunning) {
            try {
                Thread.sleep(3000);
                long currentTime = System.currentTimeMillis();
                long bytesOnThisInterval = allBytes - bytesAtTheTimeLastIteration;
                long currentSpeed =  (bytesOnThisInterval / 3) / 1024 / 1024;
                long averageSpeed =  (allBytes / ((currentTime - startTime) / 1000)) / 1024 / 1024;
                System.out.println("File: " + name + "\nCurrent speed: " + currentSpeed  + " mb, average speed: " + averageSpeed + " mb");
                bytesAtTheTimeLastIteration = allBytes;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public void stopMonitoring() {
        isRunning = false;
    }
}
