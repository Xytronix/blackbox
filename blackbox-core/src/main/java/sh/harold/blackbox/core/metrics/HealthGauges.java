package sh.harold.blackbox.core.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthGauges {

    public record World(String name, double tps, double tickMs, int players, int entities,
                        double avgPingMs, long chunks, long chunksGeneratedTotal, long chunksLoadedTotal) {
    }

    public record Sample(double tps, double tickAvgMs, int players, long heapUsedBytes, long rssBytes,
                         double cpuPct, List<World> worlds) {
        public Sample {
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }
    }

    public record IncidentKey(String trigger, String severity) {
    }

    private static final Sample EMPTY =
        new Sample(-1, -1, -1, -1, -1, -1, List.of());

    private final ConcurrentHashMap<IncidentKey, Long> incidents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> disconnects = new ConcurrentHashMap<>();
    private volatile Sample sample = EMPTY;
    private volatile long lastIncidentEpochSeconds = -1;
    private volatile long bundlesCount = -1;
    private volatile long bundlesBytes = -1;
    private volatile double collectorTimeMs = -1;
    private volatile long cpuUsageUsec = -1;
    private volatile long cpuThrottledUsec = -1;
    private volatile long cpuThrottledPeriods = -1;
    private volatile long deadlockedThreads = -1;
    private volatile long queuedPackets = -1;
    private volatile long gcPauseMsec = -1;
    private volatile long allocatedBytes = -1;
    private volatile Map<String, Long> heartbeats = Map.of();
    private volatile String version;
    private volatile Map<String, Double> pluginGauges = Map.of();
    private volatile Map<String, Double> pluginCounters = Map.of();

    public void set(Sample sample) {
        this.sample = Objects.requireNonNull(sample, "sample");
    }

    public Sample sample() {
        return sample;
    }

    public void setBundles(long count, long bytes) {
        this.bundlesCount = count;
        this.bundlesBytes = bytes;
    }

    public long bundlesCount() {
        return bundlesCount;
    }

    public long bundlesBytes() {
        return bundlesBytes;
    }

    public void setCollectorTimeMs(double ms) {
        this.collectorTimeMs = ms;
    }

    public double collectorTimeMs() {
        return collectorTimeMs;
    }

    public void setCgroupCpu(long usageUsec, long throttledUsec, long throttledPeriods) {
        this.cpuUsageUsec = usageUsec;
        this.cpuThrottledUsec = throttledUsec;
        this.cpuThrottledPeriods = throttledPeriods;
    }

    public long cpuUsageUsec() {
        return cpuUsageUsec;
    }

    public long cpuThrottledUsec() {
        return cpuThrottledUsec;
    }

    public long cpuThrottledPeriods() {
        return cpuThrottledPeriods;
    }

    public void setDeadlockedThreads(long count) {
        this.deadlockedThreads = count;
    }

    public long deadlockedThreads() {
        return deadlockedThreads;
    }

    public void setQueuedPackets(long count) {
        this.queuedPackets = count;
    }

    public long queuedPackets() {
        return queuedPackets;
    }

    public void setGcPauseMsec(long msec) {
        this.gcPauseMsec = msec;
    }

    public long gcPauseMsec() {
        return gcPauseMsec;
    }

    public void setAllocatedBytes(long bytes) {
        this.allocatedBytes = bytes;
    }

    public long allocatedBytes() {
        return allocatedBytes;
    }

    public void setHeartbeats(Map<String, Long> heartbeats) {
        this.heartbeats = heartbeats == null ? Map.of() : Map.copyOf(heartbeats);
    }

    public Map<String, Long> heartbeats() {
        return heartbeats;
    }

    public void incrementDisconnect(String reason) {
        disconnects.merge(reason == null ? "unknown" : reason, 1L, Long::sum);
    }

    public Map<String, Long> disconnects() {
        return Map.copyOf(disconnects);
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }

    public void setPluginMetrics(Map<String, Double> gauges, Map<String, Double> counters) {
        this.pluginGauges = gauges == null ? Map.of() : Map.copyOf(gauges);
        this.pluginCounters = counters == null ? Map.of() : Map.copyOf(counters);
    }

    public Map<String, Double> pluginGauges() {
        return pluginGauges;
    }

    public Map<String, Double> pluginCounters() {
        return pluginCounters;
    }

    public void incrementIncident(String trigger, String severity, long epochSeconds) {
        incidents.merge(new IncidentKey(trigger, severity), 1L, Long::sum);
        lastIncidentEpochSeconds = epochSeconds;
    }

    public Map<IncidentKey, Long> incidents() {
        return Map.copyOf(incidents);
    }

    public long lastIncidentEpochSeconds() {
        return lastIncidentEpochSeconds;
    }
}
