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
    private static final Path LINUX_MEMINFO_PATH = Path.of("/proc/meminfo");

    public SiteStatsDtos.Memory memory() {
        com.sun.management.OperatingSystemMXBean operatingSystem = operatingSystem();
        long totalBytes = Math.max(0, operatingSystem.getTotalMemorySize());
        long availableBytes = resolveAvailableMemoryBytes(totalBytes, Math.max(0, operatingSystem.getFreeMemorySize()));
        long usedBytes = Math.max(0, totalBytes - availableBytes);
        return new SiteStatsDtos.Memory(
                usedBytes,
                availableBytes,
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

    private long resolveAvailableMemoryBytes(long totalBytes, long fallbackFreeBytes) {
        if (!Files.isReadable(LINUX_MEMINFO_PATH)) {
            return fallbackFreeBytes;
        }

        try {
            for (String line : Files.readAllLines(LINUX_MEMINFO_PATH)) {
                if (line.startsWith("MemAvailable:")) {
                    long availableKilobytes = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    long availableBytes = availableKilobytes * 1024;
                    return Math.max(0, Math.min(totalBytes, availableBytes));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return fallbackFreeBytes;
        }

        return fallbackFreeBytes;
    }

    private double percent(long usedBytes, long totalBytes) {
        if (totalBytes <= 0) return 0;
        return clampPercent((double) usedBytes * 100 / (double) totalBytes);
    }

    private double clampPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }
}
