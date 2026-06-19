package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import sh.harold.blackbox.core.jfr.JfrController;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatStallDetector;
import sh.harold.blackbox.core.trigger.tick.TickDegradedDetector;

final class HytaleHealthScheduler {
    private static final int TICK_AVG_PERIOD_INDEX = 1;

    private final BlackboxRuntime runtime;
    private final System.Logger logger;
    private final ScheduledExecutorService scheduler;
    private final HytaleHeartbeatPump heartbeatPump;
    private final HytaleTelemetrySampler telemetry;
    private final HytaleNetSampler netSampler;
    private final JfrController jfr;
    private final Path rollingFile;
    private final Supplier<BlackboxRuntime.Engine> engine;

    private final AtomicBoolean stallCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRunning = new AtomicBoolean(false);
    private final AtomicBoolean tickSampleRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);

    HytaleHealthScheduler(BlackboxRuntime runtime, System.Logger logger, ScheduledExecutorService scheduler,
                          HytaleHeartbeatPump heartbeatPump, HytaleTelemetrySampler telemetry,
                          HytaleNetSampler netSampler, JfrController jfr, Path rollingFile,
                          Supplier<BlackboxRuntime.Engine> engine) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.heartbeatPump = Objects.requireNonNull(heartbeatPump, "heartbeatPump");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.netSampler = Objects.requireNonNull(netSampler, "netSampler");
        this.jfr = Objects.requireNonNull(jfr, "jfr");
        this.rollingFile = Objects.requireNonNull(rollingFile, "rollingFile");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    void startSnapshotWriter() {
        Duration interval = engine.get().config().jfrSnapshotInterval();
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleAtFixedRate(this::scheduleSnapshot, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void scheduleSnapshot() {
        if (!snapshotRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.worker().execute(() -> {
            try {
                writeRollingSnapshot();
            } finally {
                snapshotRunning.set(false);
            }
        });
    }

    private void writeRollingSnapshot() {
        try {
            Files.createDirectories(rollingFile.getParent());
            Path tmp = rollingFile.resolveSibling("rolling.jfr.tmp");
            jfr.dump(tmp);
            Files.move(tmp, rollingFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to write rolling JFR snapshot.", e);
        }
    }

    void start() {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Universe.get() failed; heartbeats disabled until restart.", e);
            return;
        }

        universe.getUniverseReady().thenRun(() -> {
            scheduler.scheduleAtFixedRate(
                heartbeatPump::tick,
                0L,
                50L,
                TimeUnit.MILLISECONDS
            );
            scheduler.scheduleAtFixedRate(
                this::scheduleStallCheck,
                250L,
                250L,
                TimeUnit.MILLISECONDS
            );
            long sampleMs = engine.get().config().jfrSampleInterval().toMillis();
            scheduler.scheduleAtFixedRate(
                this::scheduleTickSample,
                sampleMs,
                sampleMs,
                TimeUnit.MILLISECONDS
            );
            scheduler.scheduleAtFixedRate(
                this::scheduleHealthCheck,
                5_000L,
                5_000L,
                TimeUnit.MILLISECONDS
            );
        });
    }

    private void scheduleHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.worker().execute(() -> {
            try {
                BlackboxRuntime.Engine current = engine.get();
                checkDetector(current.deadlockDetector() == null
                    ? null : current.deadlockDetector()::check, "Deadlock");
                checkDetector(current.heapPressureDetector() == null
                    ? null : current.heapPressureDetector()::check, "Heap pressure");
                checkDetector(current.gcPressureDetector() == null
                    ? null : current.gcPressureDetector()::check, "GC pressure");
                checkDetector(current.cpuSaturationDetector() == null
                    ? null : current.cpuSaturationDetector()::check, "CPU saturation");
                checkDetector(current.netSaturationDetector() == null
                    ? null : current.netSaturationDetector()::check, "Net saturation");
            } finally {
                healthCheckRunning.set(false);
            }
        });
    }

    private void checkDetector(java.util.function.Supplier<java.util.List<TriggerEvent>> check, String name) {
        if (check == null) {
            return;
        }
        try {
            for (TriggerEvent event : check.get()) {
                runtime.capture(event);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, name + " detector failed.", e);
        }
    }

    void syncNetSampler() {
        netSampler.sync(engine.get().netSaturationDetector() != null);
    }

    private void scheduleTickSample() {
        if (!tickSampleRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.worker().execute(() -> {
            try {
                telemetry.sampleWorldTicks();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "World tick sampling failed.", e);
            } finally {
                tickSampleRunning.set(false);
            }
        });
    }

    private void scheduleStallCheck() {
        if (!stallCheckRunning.compareAndSet(false, true)) {
            return;
        }
        runtime.worker().execute(() -> {
            try {
                BlackboxRuntime.Engine current = engine.get();
                HeartbeatStallDetector stall = current.stallDetector();
                if (stall != null) {
                    for (TriggerEvent event : stall.check()) {
                        runtime.capture(event);
                    }
                }
                TickDegradedDetector tick = current.tickDegradedDetector();
                if (tick != null) {
                    for (TriggerEvent event : tick.check()) {
                        runtime.capture(event);
                    }
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Stall check failed.", e);
            } finally {
                stallCheckRunning.set(false);
            }
        });
    }

    static Map<String, Double> tickAverageMillis() {
        Map<String, Double> out = new HashMap<>();
        try {
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String scope = entry.getKey();
                World world = entry.getValue();
                if (scope == null || scope.isBlank() || world == null) {
                    continue;
                }
                try {
                    double avgDeltaNanos = world.getBufferedTickLengthMetricSet().getAverage(TICK_AVG_PERIOD_INDEX);
                    if (avgDeltaNanos > 0) {
                        out.put(scope, avgDeltaNanos / 1_000_000.0);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
