package sh.harold.blackbox.hytale;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import sh.harold.blackbox.core.config.BlackboxConfig;
import sh.harold.blackbox.core.health.HostCpuStats;
import sh.harold.blackbox.core.jfr.BlackboxDiskEvent;
import sh.harold.blackbox.core.jfr.BlackboxHostCpuEvent;
import sh.harold.blackbox.core.jfr.BlackboxSystemTickEvent;
import sh.harold.blackbox.core.jfr.BlackboxWorldTickEvent;
import sh.harold.blackbox.core.metrics.HealthGauges;
import sh.harold.blackbox.core.metrics.MetricsLog;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatRegistry;
import sh.harold.blackbox.core.trigger.player.PlayerDropDetector;

final class HytaleTelemetrySampler {
    private static final int SYSTEM_TICK_PER_WORLD_LIMIT = 4;

    private final Clock clock;
    private final System.Logger logger;
    private final HytaleWorldStatsProvider worldStats;
    private final MetricsLog metricsLog;
    private final HealthGauges healthGauges;
    private final HeartbeatRegistry heartbeatRegistry;
    private final Supplier<BlackboxConfig> config;
    private final Supplier<PlayerDropDetector> playerDrop;
    private final Consumer<TriggerEvent> capture;
    private long lastGcMillis = -1;
    private long lastGcWallMillis;
    private long lastAllocBytes = -1;
    private Instant lastAllocAt;

    HytaleTelemetrySampler(Clock clock, System.Logger logger, HytaleWorldStatsProvider worldStats,
                           MetricsLog metricsLog, HealthGauges healthGauges, HeartbeatRegistry heartbeatRegistry,
                           Supplier<BlackboxConfig> config,
                           Supplier<PlayerDropDetector> playerDrop, Consumer<TriggerEvent> capture) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.worldStats = Objects.requireNonNull(worldStats, "worldStats");
        this.metricsLog = Objects.requireNonNull(metricsLog, "metricsLog");
        this.healthGauges = Objects.requireNonNull(healthGauges, "healthGauges");
        this.heartbeatRegistry = Objects.requireNonNull(heartbeatRegistry, "heartbeatRegistry");
        this.config = Objects.requireNonNull(config, "config");
        this.playerDrop = Objects.requireNonNull(playerDrop, "playerDrop");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    void sampleWorldTicks() {
        long startNanos = System.nanoTime();
        int totalPlayers = 0;
        boolean playersKnown = false;
        long totalChunks = 0;
        boolean chunksKnown = false;
        double worstTps = -1;
        double worstMspt = -1;
        List<HealthGauges.World> worldGauges = new ArrayList<>();
        for (sh.harold.blackbox.core.health.HealthSnapshot.World world : worldStats.worlds().worlds()) {
            if (world.players() >= 0) {
                totalPlayers += world.players();
                playersKnown = true;
            }
            if (world.chunks() >= 0) {
                totalChunks += world.chunks();
                chunksKnown = true;
            }
            worldGauges.add(new HealthGauges.World(world.name(), world.tps(), world.mspt(),
                world.players(), world.entities(), world.avgPingMs(), world.chunks(),
                world.chunksGeneratedTotal(), world.chunksLoadedTotal()));
            if (world.tps() < 0) {
                continue;
            }
            worstTps = worstTps < 0 ? world.tps() : Math.min(worstTps, world.tps());
            if (world.mspt() >= 0) {
                worstMspt = Math.max(worstMspt, world.mspt());
            }
            BlackboxWorldTickEvent event = new BlackboxWorldTickEvent();
            event.world = world.name();
            event.tps = world.tps();
            event.mspt = world.mspt();
            event.players = world.players();
            event.entities = world.entities();
            event.chunks = world.chunks();
            event.chunksGeneratedTotal = world.chunksGeneratedTotal();
            event.chunksLoadedTotal = world.chunksLoadedTotal();
            event.avgPingMs = world.avgPingMs();
            event.commit();
        }
        if (chunksKnown) {
            BlackboxApi.recordGauge("blackbox", "Chunks loaded", totalChunks);
        }
        recordHealthMetrics(worstMspt, worstTps, playersKnown ? totalPlayers : -1, worldGauges,
            BlackboxApi.sampleSupplierGauges());
        sampleDisk();
        sampleHostCpu();
        PlayerDropDetector drop = playerDrop.get();
        if (drop != null && playersKnown) {
            for (TriggerEvent event : drop.sample(totalPlayers)) {
                capture.accept(event);
            }
        }
        try {
            for (HytaleTickSystems.SystemSample sample : HytaleTickSystems.topSystems(SYSTEM_TICK_PER_WORLD_LIMIT)) {
                BlackboxSystemTickEvent event = new BlackboxSystemTickEvent();
                event.world = sample.world();
                event.system = sample.system();
                event.avgMs = sample.avgMs();
                event.commit();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "System tick sampling failed.", e);
        }
        healthGauges.setCollectorTimeMs((System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private void recordHealthMetrics(double tickAvgMs, double tps, int players,
                                     List<HealthGauges.World> worlds, Map<String, Double> supplierGauges) {
        BlackboxConfig cfg = config.get();
        boolean csv = cfg.metricsEnabled();
        boolean live = cfg.prometheusEnabled();
        if (!csv && !live) {
            return;
        }
        try {
            Instant now = clock.instant();
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long rss = readRssBytes();
            double gcPct = gcFractionPercent();
            double cpu = cfg.metricsCpu() ? JvmProbes.processCpuLoad() : -1;
            double cpuPct = cpu < 0 ? -1 : cpu * 100;
            double alloc = cfg.metricsAllocation() ? allocRateMbPerSec(now) : Double.NaN;
            if (live) {
                healthGauges.set(new HealthGauges.Sample(tps, tickAvgMs, players, heapUsed, rss,
                    gcPct, cpuPct, Double.isNaN(alloc) ? -1 : alloc, worlds));
                long[] cgroupCpu = HostCpuStats.cgroupThrottle();
                if (cgroupCpu != null) {
                    healthGauges.setCgroupCpu(cgroupCpu[2], cgroupCpu[1], cgroupCpu[0]);
                }
                healthGauges.setDeadlockedThreads(JvmProbes.deadlockedThreadCount());
                healthGauges.setQueuedPackets(sampleQueuedPackets());
                healthGauges.setHeartbeats(heartbeatEpochSeconds());
                Map<String, Double> gauges = new HashMap<>(BlackboxApi.pluginGauges());
                gauges.putAll(supplierGauges);
                healthGauges.setPluginMetrics(gauges, BlackboxApi.pluginCounters());
            }
            if (csv) {
                String row = MetricsLog.formatRow(now, tickAvgMs, tps, players,
                    heapUsed, rss, gcPct, cpuPct, alloc);
                metricsLog.append(now, row, cfg.metricsRetentionDays());
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to record health metrics.", e);
        }
    }

    private double allocRateMbPerSec(Instant now) {
        long bytes = JvmProbes.allocatedBytes();
        if (bytes < 0) {
            return Double.NaN;
        }
        double rate = Double.NaN;
        if (lastAllocBytes >= 0 && lastAllocAt != null) {
            double seconds = (now.toEpochMilli() - lastAllocAt.toEpochMilli()) / 1000.0;
            if (seconds > 0 && bytes >= lastAllocBytes) {
                rate = (bytes - lastAllocBytes) / seconds / (1024 * 1024);
            }
        }
        lastAllocBytes = bytes;
        lastAllocAt = now;
        return rate;
    }

    private double gcFractionPercent() {
        long gcNow = JvmProbes.stwGcPauseMillis();
        if (gcNow < 0) {
            gcNow = 0;
        }
        long wallNow = clock.millis();
        double percent = -1;
        if (lastGcMillis >= 0) {
            long wallDelta = wallNow - lastGcWallMillis;
            if (wallDelta > 0) {
                percent = (gcNow - lastGcMillis) * 100.0 / wallDelta;
            }
        }
        lastGcMillis = gcNow;
        lastGcWallMillis = wallNow;
        return percent;
    }

    private long readRssBytes() {
        try {
            Path statm = Path.of("/proc/self/statm");
            if (!Files.isReadable(statm)) {
                return -1;
            }
            String[] fields = Files.readString(statm).trim().split("\\s+");
            if (fields.length < 2) {
                return -1;
            }
            return Long.parseLong(fields[1]) * 4096L;
        } catch (Exception e) {
            return -1;
        }
    }

    private long sampleQueuedPackets() {
        try {
            long total = 0;
            for (var player : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
                if (player == null) {
                    continue;
                }
                var handler = player.getPacketHandler();
                if (handler != null) {
                    total += handler.getQueuedPacketsCount();
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private Map<String, Long> heartbeatEpochSeconds() {
        Map<String, Instant> snapshot = heartbeatRegistry.snapshot();
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>(snapshot.size());
        snapshot.forEach((scope, instant) -> {
            if (instant != null) {
                out.put(scope, instant.getEpochSecond());
            }
        });
        return out;
    }

    private void sampleHostCpu() {
        try {
            long[] throttle = HostCpuStats.cgroupThrottle();
            long[] proc = HostCpuStats.procCpu();
            if (throttle == null && proc == null) {
                return;
            }
            BlackboxHostCpuEvent event = new BlackboxHostCpuEvent();
            event.throttledPeriods = throttle == null ? -1 : throttle[0];
            event.throttledMicros = throttle == null ? -1 : throttle[1];
            event.cpuUsageUsec = throttle == null ? -1 : throttle[2];
            event.cpuTotalJiffies = proc == null ? -1 : proc[0];
            event.cpuStealJiffies = proc == null ? -1 : proc[1];
            event.cpuIowaitJiffies = proc == null ? -1 : proc[2];
            event.commit();
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Host CPU sampling failed.", e);
        }
    }

    private void sampleDisk() {
        try {
            java.nio.file.FileStore store = Files.getFileStore(java.nio.file.Path.of("."));
            BlackboxDiskEvent event = new BlackboxDiskEvent();
            event.freeBytes = store.getUsableSpace();
            event.totalBytes = store.getTotalSpace();
            long[] io = processIoBytes();
            event.readBytes = io[0];
            event.writeBytes = io[1];
            event.commit();
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Disk sampling failed.", e);
        }
    }

    private static long[] processIoBytes() {
        long[] io = {-1L, -1L};
        try {
            java.nio.file.Path procIo = java.nio.file.Path.of("/proc/self/io");
            if (!Files.isReadable(procIo)) {
                return io;
            }
            for (String line : Files.readAllLines(procIo)) {
                if (line.startsWith("read_bytes:")) {
                    io[0] = Long.parseLong(line.substring("read_bytes:".length()).trim());
                } else if (line.startsWith("write_bytes:")) {
                    io[1] = Long.parseLong(line.substring("write_bytes:".length()).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return io;
    }
}
