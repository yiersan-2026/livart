package com.artisanlab.stats;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class SystemResourceStatsService {
    private static final Path DISK_STATS_PATH = Path.of("/");

    public SiteStatsDtos.Memory memory() {
        com.sun.management.OperatingSystemMXBean operatingSystem = operatingSystem();
        long totalBytes = Math.max(0, operatingSystem.getTotalMemorySize());
        long freeBytes = Math.max(0, operatingSystem.getFreeMemorySize());
        long usedBytes = Math.max(0, totalBytes - freeBytes);
        return new SiteStatsDtos.Memory(
                usedBytes,
                freeBytes,
                totalBytes,
                percent(usedBytes, totalBytes)
        );
    }

    public SiteStatsDtos.Processor processor() {
        com.sun.management.OperatingSystemMXBean operatingSystem = operatingSystem();
        double cpuLoad = operatingSystem.getCpuLoad();
        double usedPercent = cpuLoad < 0 ? 0 : clampPercent(cpuLoad * 100);
        return new SiteStatsDtos.Processor(
                usedPercent,
                operatingSystem.getAvailableProcessors()
        );
    }

    public SiteStatsDtos.Disk disk() {
        try {
            FileStore fileStore = Files.getFileStore(DISK_STATS_PATH);
            long totalBytes = Math.max(0, fileStore.getTotalSpace());
            long freeBytes = Math.max(0, fileStore.getUsableSpace());
            long usedBytes = Math.max(0, totalBytes - freeBytes);
            return new SiteStatsDtos.Disk(
                    DISK_STATS_PATH.toString(),
                    usedBytes,
                    freeBytes,
                    totalBytes,
                    percent(usedBytes, totalBytes)
            );
        } catch (IOException exception) {
            return new SiteStatsDtos.Disk(
                    DISK_STATS_PATH.toString(),
                    0,
                    0,
                    0,
                    0
            );
        }
    }

    private com.sun.management.OperatingSystemMXBean operatingSystem() {
        return (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    private double percent(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) return 0;
        return clampPercent((double) usedBytes * 100 / (double) totalBytes);
    }

    private double clampPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }
}
