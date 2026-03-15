package org.example.model;

/**
 * Snapshot of server metrics collected from Zabbix.
 */
public class Metrics {

    private double cpu;
    private double ram;
    private double disk;
    private double networkIn;
    private double networkOut;
    private long timestamp;

    public Metrics() {}

    public Metrics(double cpu, double ram, double disk,
                   double networkIn, double networkOut, long timestamp) {
        this.cpu = cpu;
        this.ram = ram;
        this.disk = disk;
        this.networkIn = networkIn;
        this.networkOut = networkOut;
        this.timestamp = timestamp;
    }

    public double getCpu() { return cpu; }
    public void setCpu(double cpu) { this.cpu = cpu; }

    public double getRam() { return ram; }
    public void setRam(double ram) { this.ram = ram; }

    public double getDisk() { return disk; }
    public void setDisk(double disk) { this.disk = disk; }

    public double getNetworkIn() { return networkIn; }
    public void setNetworkIn(double networkIn) { this.networkIn = networkIn; }

    public double getNetworkOut() { return networkOut; }
    public void setNetworkOut(double networkOut) { this.networkOut = networkOut; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
