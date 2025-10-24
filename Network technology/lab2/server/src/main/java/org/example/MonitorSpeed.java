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
                int timeSleep = 3000;
                Thread.sleep(timeSleep);
                long currentTime = System.currentTimeMillis();
                long bytesOnThisInterval = allBytes - bytesAtTheTimeLastIteration;
                double seconds = 3.0;
                double kilobyteToByte = 1024.0;
                double currentSpeed =  (bytesOnThisInterval / seconds) / kilobyteToByte / kilobyteToByte;
                double secondsToMilliseconds = 1000.0;
                double averageSpeed =  (allBytes / ((currentTime - startTime) / secondsToMilliseconds)) / kilobyteToByte / kilobyteToByte;
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
