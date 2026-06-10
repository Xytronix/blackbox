package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import jdk.jfr.consumer.RecordingStream;
import sh.harold.blackbox.core.bundle.BundleBuilder;
import sh.harold.blackbox.core.bundle.BundleExtrasRegistry;
import sh.harold.blackbox.core.capture.CapturePipeline;
import sh.harold.blackbox.core.capture.IncidentNotifier;
import sh.harold.blackbox.core.capture.PostIncidentWaiter;
import sh.harold.blackbox.core.capture.RecordingDumper;
import sh.harold.blackbox.core.config.BlackboxConfig;
import sh.harold.blackbox.core.jfr.BlackboxDiskEvent;
import sh.harold.blackbox.core.jfr.BlackboxSystemTickEvent;
import sh.harold.blackbox.core.jfr.BlackboxWorldTickEvent;
import sh.harold.blackbox.core.jfr.JfrController;
import sh.harold.blackbox.core.notify.discord.DiscordWebhookNotifier;
import sh.harold.blackbox.core.notify.discord.HttpClientWebhookTransport;
import sh.harold.blackbox.core.retention.FileDeleter;
import sh.harold.blackbox.core.retention.RetentionManager;
import sh.harold.blackbox.core.trigger.DetectorPolicy;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;
import sh.harold.blackbox.core.trigger.TriggerEngine;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatRegistry;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatStallDetector;
import sh.harold.blackbox.core.trigger.jvm.CpuSaturationDetector;
import sh.harold.blackbox.core.trigger.jvm.DeadlockDetector;
import sh.harold.blackbox.core.trigger.jvm.GcPressureDetector;
import sh.harold.blackbox.core.trigger.jvm.HeapPressureDetector;
import sh.harold.blackbox.core.trigger.net.NetSaturationDetector;
import sh.harold.blackbox.core.trigger.player.PlayerDropDetector;
import sh.harold.blackbox.core.trigger.tick.TickDegradedDetector;

final class BlackboxRuntime implements AutoCloseable {
    private final BlackboxPlugin plugin;
    private final Clock clock;
    private final System.Logger logger;
    private final Path dataDir;
    private final Path configPath;
    private final Path incidentDir;
    private final Path tempDir;
    private final Path rollingFile;
    private final Path recoverDir;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;

    private final JfrController jfr;
    private final HeartbeatRegistry heartbeatRegistry;
    private final BundleExtrasRegistry extrasRegistry;

    /**
     * Everything rebuilt from config so {@code /blackbox reload} can swap it atomically while
     * scheduler ticks keep reading a consistent snapshot. The JFR recording is deliberately not
     * part of this; Jfr.* changes require a restart.
     */
    private record Engine(
        BlackboxConfig config,
        HeartbeatStallDetector stallDetector,
        TickDegradedDetector tickDegradedDetector,
        CapturePipeline capturePipeline,
        DeadlockDetector deadlockDetector,
        HeapPressureDetector heapPressureDetector,
        GcPressureDetector gcPressureDetector,
        CpuSaturationDetector cpuSaturationDetector,
        NetSaturationDetector netSaturationDetector,
        PlayerDropDetector playerDropDetector
    ) {}

    private volatile Engine engine;

    private final AtomicBoolean stallCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRunning = new AtomicBoolean(false);
    private final AtomicBoolean tickSampleRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean profileActive = new AtomicBoolean(false);
    private volatile long profileEndsAtMs;
    private final HytaleWorldStatsProvider worldStats;

    /** Latest per-interface throughput observed by the net sampler: iface to {inMbps, outMbps, epochMs}. */
    private final Map<String, double[]> netRatesByInterface = new ConcurrentHashMap<>();
    private volatile RecordingStream netStream;
    private final Map<String, AtomicBoolean> heartbeatPending = new ConcurrentHashMap<>();
    private int heartbeatSweepCounter;
    private final AtomicReference<Instant> lastIncidentAt = new AtomicReference<>();
    private final AtomicReference<String> lastIncidentId = new AtomicReference<>();
    private final AtomicBoolean capturePending = new AtomicBoolean(false);

    static BlackboxRuntime start(BlackboxPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        System.Logger logger = System.getLogger(BlackboxRuntime.class.getName());

        Path dataDir = plugin.getDataDirectory();
        BlackboxConfig config = HytaleBlackboxConfig.loadOrCreate(dataDir, logger);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("blackbox-scheduler")
        );
        ExecutorService worker = Executors.newSingleThreadExecutor(new NamedThreadFactory("blackbox-worker"));

        Clock clock = Clock.systemUTC();

        JfrController jfr = new JfrController(
            config.jfrMaxAge(), config.jfrMaxSizeBytes(),
            config.jfrRecordingName(), config.jfrDisabledEvents(),
            config.jfrConfiguration()
        );
        jfr.start();

        BlackboxRuntime runtime = new BlackboxRuntime(plugin, clock, logger, dataDir, scheduler, worker, jfr);
        runtime.engine = runtime.buildEngine(config);
        runtime.extrasRegistry.register(new HytaleBundleExtrasProvider(
            runtime.heartbeatRegistry,
            () -> runtime.engine.config().capturePolicy().logTailLines()));
        runtime.syncNetSampler();
        runtime.recoverOrphanedRecordings();
        runtime.startSnapshotWriter();
        runtime.startScheduledWork();
        runtime.registerCommands();
        runtime.registerWorldFailureListener();
        BlackboxApi.registerDiagnostics("Environment", HytaleEnvironment::snapshot);
        runtime.logStartup();
        return runtime;
    }

    /** Builds the config-derived state; everything here is replaceable by {@code /blackbox reload}. */
    private Engine buildEngine(BlackboxConfig config) {
        HeartbeatStallDetector stallDetector = new HeartbeatStallDetector(
            clock,
            heartbeatRegistry,
            config.triggerPolicy().stallDegradedMs()
        );
        TickDegradedDetector tickDegradedDetector = new TickDegradedDetector(
            clock,
            BlackboxRuntime::tickAverageMillis,
            config.triggerPolicy().tickAvgDegradedMs()
        );
        TriggerEngine triggerEngine = new TriggerEngine(clock, config.triggerPolicy());

        RecordingDumper dumper = (target) -> {
            try {
                jfr.dump(target);
                return target;
            } finally {
                capturePending.set(false);
            }
        };
        PostIncidentWaiter postIncidentWaiter = (event) -> {
            capturePending.set(true);
            awaitHeartbeatRecovery(event, heartbeatRegistry, clock, config.postIncidentMaxWait(), logger);
        };
        IncidentNotifier notifier = buildNotifier(clock, config, logger, worker);

        CapturePipeline capturePipeline = new CapturePipeline(
            clock,
            triggerEngine,
            dumper,
            new BundleBuilder(clock, logger),
            new RetentionManager(clock, logger, FileDeleter.defaultDeleter()),
            notifier,
            extrasRegistry,
            worldStats,
            () -> HytaleModConfigs.appendTo(HytaleMixins.appendTo(HytalePlugins.appendTo(
                HytaleEntities.appendTo(HytaleTickSystems.appendTo(HytaleServerLog.appendTo(
                    BlackboxApi.collectDiagnostics(), config.capturePolicy().logTailLines())))))),
            postIncidentWaiter,
            incidentDir,
            tempDir,
            config.capturePolicy(),
            logger
        );

        DetectorPolicy detectors = config.triggerPolicy().detectors();
        return new Engine(
            config,
            stallDetector,
            tickDegradedDetector,
            capturePipeline,
            detectors.deadlock()
                ? new DeadlockDetector(clock, BlackboxRuntime::deadlockedThreadIds)
                : null,
            detectors.heapPressurePct() > 0
                ? new HeapPressureDetector(clock, BlackboxRuntime::tenuredAfterGcFraction,
                    detectors.heapPressurePct() / 100.0, detectors.heapPressureSustain())
                : null,
            detectors.gcPressurePct() > 0 && !detectors.gcPressureWindow().isZero()
                ? new GcPressureDetector(clock, BlackboxRuntime::totalGcTimeMillis,
                    detectors.gcPressurePct() / 100.0, detectors.gcPressureWindow())
                : null,
            detectors.cpuSaturationPct() > 0
                ? new CpuSaturationDetector(clock, BlackboxRuntime::processCpuLoad,
                    detectors.cpuSaturationPct() / 100.0, detectors.cpuSaturationSustain())
                : null,
            detectors.netInMbps() > 0 || detectors.netOutMbps() > 0
                ? new NetSaturationDetector(clock, this::latestNetRates,
                    detectors.netInMbps(), detectors.netOutMbps(), detectors.netSustain())
                : null,
            detectors.playerDropPct() > 0 && !detectors.playerDropWindow().isZero()
                ? new PlayerDropDetector(clock, detectors.playerDropPct() / 100.0,
                    detectors.playerDropWindow(), detectors.playerDropMinPlayers())
                : null
        );
    }

    private static IncidentNotifier buildNotifier(
        Clock clock,
        BlackboxConfig config,
        System.Logger logger,
        ExecutorService worker
    ) {
        if (config.discordWebhook().webhookUrl().isBlank()) {
            return IncidentNotifier.noop();
        }
        return new DiscordWebhookNotifier(
            clock,
            logger,
            config.discordWebhook(),
            new HttpClientWebhookTransport(config.discordWebhook().requestTimeout()),
            worker
        );
    }

    private BlackboxRuntime(
        BlackboxPlugin plugin,
        Clock clock,
        System.Logger logger,
        Path dataDir,
        ScheduledExecutorService scheduler,
        ExecutorService worker,
        JfrController jfr
    ) {
        this.plugin = plugin;
        this.clock = clock;
        this.logger = logger;
        this.dataDir = dataDir;
        this.configPath = HytaleBlackboxConfig.path(dataDir);
        this.incidentDir = dataDir.resolve("incidents");
        this.tempDir = dataDir.resolve("temp");
        this.rollingFile = dataDir.resolve("live").resolve("rolling.jfr");
        this.recoverDir = dataDir.resolve("live").resolve("recover");
        this.scheduler = scheduler;
        this.worker = worker;
        this.jfr = jfr;
        this.heartbeatRegistry = new HeartbeatRegistry(clock);
        this.extrasRegistry = new BundleExtrasRegistry(logger);
        this.worldStats = new HytaleWorldStatsProvider();
    }

    void registerCommands() {
        try {
            BlackboxCommand command = new BlackboxCommand(this);
            command.setOwner(plugin);
            plugin.getCommandRegistry().registerCommand(command);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to register /blackbox command.", e);
        }
    }

    private void registerWorldFailureListener() {
        try {
            plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onRemoveWorld);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to RemoveWorldEvent; world-failure captures disabled.", e);
        }
    }

    /**
     * Fires a WORLD_FAILURE capture when a world is removed because its thread died from an uncaught
     * exception. The capture is offloaded to the worker so the engine's event dispatch is never
     * blocked by the JFR dump and bundle build.
     */
    private void onRemoveWorld(RemoveWorldEvent event) {
        if (event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        String scope = event.getWorld().getName();
        if (scope == null || scope.isBlank()) {
            scope = "unknown-world";
        }
        TriggerEvent trigger = new TriggerEvent(
            TriggerKind.WORLD_FAILURE,
            scope,
            clock.instant(),
            failureAttrs(event.getWorld().getFailureException())
        );
        try {
            worker.execute(() -> capture(trigger));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to schedule world-failure capture for " + scope, e);
        }
    }

    private static Map<String, String> failureAttrs(Throwable failure) {
        if (failure == null) {
            return Map.of();
        }
        String error = failure.getClass().getName();
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            error += ": " + message;
        }
        StackTraceElement[] frames = failure.getStackTrace();
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < frames.length && i < 5; i++) {
            if (i > 0) {
                stack.append('\n');
            }
            stack.append("at ").append(frames[i]);
        }
        if (stack.isEmpty()) {
            return Map.of("error", error);
        }
        return Map.of("error", error, "errorStack", stack.toString());
    }

    Optional<String> captureManual() {
        TriggerEvent event = new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(), java.util.Map.of());
        return capture(event);
    }

    Optional<String> capture(TriggerEvent event) {
        Optional<String> id;
        try {
            id = engine.capturePipeline().handle(event).map(incidentId -> incidentId.value());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Capture pipeline threw unexpectedly.", e);
            return Optional.empty();
        }

        if (id.isPresent()) {
            lastIncidentAt.set(event.at());
            lastIncidentId.set(id.get());
        }
        return id;
    }

    Path incidentDir() {
        return incidentDir;
    }

    Path dataDir() {
        return dataDir;
    }

    Path configPath() {
        return configPath;
    }

    BlackboxConfig config() {
        return engine.config();
    }

    boolean deadlockArmed() {
        return engine.deadlockDetector() != null;
    }

    boolean heapPressureArmed() {
        return engine.heapPressureDetector() != null;
    }

    boolean gcPressureArmed() {
        return engine.gcPressureDetector() != null;
    }

    boolean cpuSaturationArmed() {
        return engine.cpuSaturationDetector() != null;
    }

    boolean netSaturationArmed() {
        return engine.netSaturationDetector() != null;
    }

    boolean playerDropArmed() {
        return engine.playerDropDetector() != null;
    }

    /**
     * Re-reads blackbox.json and swaps the config-derived state. The live JFR recording is never
     * touched; Jfr.* changes are reported as restart-required. A failed rebuild keeps the
     * previous state.
     */
    String reload() {
        BlackboxConfig previous = engine.config();
        BlackboxConfig fresh;
        Engine next;
        try {
            fresh = HytaleBlackboxConfig.loadOrCreate(dataDir, logger);
            next = buildEngine(fresh);
        } catch (Exception e) {
            return "Reload failed: " + e + ", keeping previous config.";
        }
        this.engine = next;
        syncNetSampler();

        List<String> changed = new ArrayList<>();
        if (!previous.triggerPolicy().equals(fresh.triggerPolicy())) {
            changed.add("Trigger");
        }
        if (!previous.capturePolicy().equals(fresh.capturePolicy())) {
            changed.add("Capture/Retention");
        }
        if (!previous.discordWebhook().equals(fresh.discordWebhook())) {
            changed.add("Discord");
        }
        if (!previous.postIncidentMaxWait().equals(fresh.postIncidentMaxWait())) {
            changed.add("Jfr.PostIncidentMaxWait");
        }

        List<String> restartRequired = new ArrayList<>();
        if (!previous.jfrMaxAge().equals(fresh.jfrMaxAge())
            || previous.jfrMaxSizeBytes() != fresh.jfrMaxSizeBytes()
            || !previous.jfrRecordingName().equals(fresh.jfrRecordingName())
            || !previous.jfrDisabledEvents().equals(fresh.jfrDisabledEvents())
            || !previous.jfrConfiguration().equals(fresh.jfrConfiguration())) {
            restartRequired.add("Jfr recording settings");
        }
        if (!previous.jfrSnapshotInterval().equals(fresh.jfrSnapshotInterval())) {
            restartRequired.add("Jfr.SnapshotInterval");
        }
        if (!previous.jfrSampleInterval().equals(fresh.jfrSampleInterval())) {
            restartRequired.add("Jfr.SampleInterval");
        }

        StringBuilder out = new StringBuilder("Reloaded. Changed: ")
            .append(changed.isEmpty() ? "nothing" : String.join(", ", changed));
        if (!restartRequired.isEmpty()) {
            out.append(". Restart required for: ").append(String.join(", ", restartRequired));
        }
        return out.toString();
    }

    /**
     * Starts an on-demand high-fidelity profiling session: discards the rolling buffer, records
     * with the "profile" preset for the given minutes, captures a manual incident, then reverts
     * to the configured preset. Returns null on success, otherwise the reason it did not start.
     */
    String startProfileSession(int minutes) {
        int clamped = Math.max(1, Math.min(30, minutes));
        if (!profileActive.compareAndSet(false, true)) {
            return "A profile session is already running (" + profileRemainingSeconds() + "s remaining).";
        }
        try {
            jfr.restart("profile");
        } catch (Exception e) {
            profileActive.set(false);
            return "Failed to switch the recording to the profile preset: " + e;
        }
        profileEndsAtMs = clock.millis() + clamped * 60_000L;
        scheduler.schedule(() -> worker.execute(this::finishProfileSession), clamped, TimeUnit.MINUTES);
        return null;
    }

    boolean profileActive() {
        return profileActive.get();
    }

    long profileRemainingSeconds() {
        return Math.max(0, (profileEndsAtMs - clock.millis()) / 1000);
    }

    private void finishProfileSession() {
        try {
            capture(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                Map.of("reason", "profile session")));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Profile-session capture failed.", e);
        } finally {
            try {
                jfr.restart(null);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to revert the recording preset.", e);
            }
            profileActive.set(false);
        }
    }

    BundleExtrasRegistry extrasRegistry() {
        return extrasRegistry;
    }

    ExecutorService worker() {
        return worker;
    }

    Optional<String> lastIncidentId() {
        return Optional.ofNullable(lastIncidentId.get());
    }

    Optional<Instant> lastIncidentAt() {
        return Optional.ofNullable(lastIncidentAt.get());
    }

    private void logStartup() {
        try {
            plugin.getLogger().at(Level.INFO).log("Blackbox started.");
            plugin.getLogger().at(Level.INFO).log("Config: %s", configPath);
        } catch (Exception e) {
            logger.log(System.Logger.Level.INFO, "Blackbox started.");
        }
    }

    /**
     * Rebuilds incident bundles from rolling snapshots left behind by an unclean shutdown. Any snapshot
     * from a previous run is moved aside into {@code live/recover} first (so the snapshot writer can reuse
     * {@code live/rolling.jfr} without racing the scan), then the scan runs on the worker so startup is
     * not blocked by JFR parsing.
     */
    private void recoverOrphanedRecordings() {
        if (engine.config().jfrSnapshotInterval().isZero()) {
            return;
        }
        try {
            Files.createDirectories(recoverDir);
            if (Files.exists(rollingFile)) {
                Path staged = recoverDir.resolve("rolling-" + clock.millis() + ".jfr");
                Files.move(rollingFile, staged, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to stage an orphaned recording for recovery.", e);
        }
        worker.execute(() -> {
            try {
                engine.capturePipeline().recoverOrphans(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery scan failed.", e);
            }
        });
    }

    /**
     * Periodically dumps the rolling recording to {@code live/rolling.jfr} so a hard crash can be turned
     * into an incident bundle on the next startup. Disabled when {@code Jfr.SnapshotInterval} is zero.
     */
    private void startSnapshotWriter() {
        Duration interval = engine.config().jfrSnapshotInterval();
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        long millis = Math.max(1000L, interval.toMillis());
        scheduler.scheduleAtFixedRate(this::scheduleSnapshot, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Offloads the rolling-snapshot dump to the worker so the scheduler thread (which also drives heartbeat
     * ticks) is never blocked by JFR I/O. Skips this tick if a snapshot is still in flight.
     */
    private void scheduleSnapshot() {
        if (!snapshotRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
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

    private void startScheduledWork() {
        Universe universe;
        try {
            universe = Universe.get();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Universe.get() failed; heartbeats disabled until restart.", e);
            return;
        }

        universe.getUniverseReady().thenRun(() -> {
            scheduler.scheduleAtFixedRate(
                this::tickHeartbeats,
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
            long sampleMs = engine.config().jfrSampleInterval().toMillis();
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

    /**
     * Polls the background health detectors (deadlock, heap/GC/CPU pressure, network saturation).
     * Offloaded to the worker like the stall check; skipped while a previous poll is in flight.
     */
    private void scheduleHealthCheck() {
        if (!healthCheckRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                Engine current = engine;
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
                capture(event);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, name + " detector failed.", e);
        }
    }

    /**
     * Streams {@code jdk.NetworkUtilization} into {@link #netRatesByInterface} for the network
     * saturation detector. Only started when a network threshold is configured; the stream is its
     * own lightweight recording and is closed on shutdown.
     */
    private synchronized void syncNetSampler() {
        boolean armed = engine.netSaturationDetector() != null;
        if (!armed && netStream != null) {
            try {
                netStream.close();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Network sampler shutdown failed.", e);
            }
            netStream = null;
            return;
        }
        if (!armed || netStream != null) {
            return;
        }
        try {
            RecordingStream stream = new RecordingStream();
            stream.enable("jdk.NetworkUtilization").withPeriod(Duration.ofSeconds(5));
            stream.onEvent("jdk.NetworkUtilization", event -> {
                try {
                    String iface = event.getString("networkInterface");
                    if (iface == null || iface.isBlank()) {
                        return;
                    }
                    netRatesByInterface.put(iface, new double[] {
                        event.getLong("readRate") / 1e6,
                        event.getLong("writeRate") / 1e6,
                        clock.millis()
                    });
                } catch (Exception ignored) {
                }
            });
            stream.startAsync();
            this.netStream = stream;
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to start the network sampler; net-saturation captures disabled.", e);
        }
    }

    /**
     * Latest throughput for the saturation detector: the per-direction MAX across interfaces (not
     * the sum, which would double-count loopback chatter). Null until fresh samples exist.
     */
    private NetSaturationDetector.Rates latestNetRates() {
        long cutoff = clock.millis() - 15_000L;
        double in = -1;
        double out = -1;
        for (double[] rates : netRatesByInterface.values()) {
            if (rates[2] < cutoff) {
                continue;
            }
            in = Math.max(in, rates[0]);
            out = Math.max(out, rates[1]);
        }
        return in < 0 ? null : new NetSaturationDetector.Rates(in, out);
    }

    private static long[] deadlockedThreadIds() {
        try {
            return ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        } catch (Exception e) {
            return null;
        }
    }

    private static double processCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getProcessCpuLoad();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static long totalGcTimeMillis() {
        try {
            long total = 0;
            boolean any = false;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long time = gc.getCollectionTime();
                if (time >= 0) {
                    total += time;
                    any = true;
                }
            }
            return any ? total : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * After-GC occupancy fraction of the tenured pool. Prefers an Old/Tenured pool (G1, Parallel,
     * Serial); single-generation collectors (Shenandoah, ZGC) expose one heap pool, which is used
     * as-is. NaN before the first collection or when the pool exposes no usable maximum.
     */
    private static double tenuredAfterGcFraction() {
        try {
            MemoryPoolMXBean chosen = null;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.HEAP) {
                    continue;
                }
                String name = pool.getName().toLowerCase(Locale.ROOT);
                if (name.contains("old") || name.contains("tenured")) {
                    chosen = pool;
                    break;
                }
                if (chosen == null) {
                    chosen = pool;
                }
            }
            if (chosen == null) {
                return Double.NaN;
            }
            MemoryUsage afterGc = chosen.getCollectionUsage();
            if (afterGc == null || (afterGc.getUsed() == 0 && afterGc.getCommitted() == 0)) {
                return Double.NaN;
            }
            long max = afterGc.getMax();
            if (max <= 0 && chosen.getUsage() != null) {
                max = chosen.getUsage().getMax();
            }
            if (max <= 0) {
                return Double.NaN;
            }
            return afterGc.getUsed() / (double) max;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Commits a {@link BlackboxWorldTickEvent} per world every 10s so the rolling recording carries
     * TPS/MSPT/player history. Offloaded to the worker; skipped while a previous sample is in flight.
     */
    private void scheduleTickSample() {
        if (!tickSampleRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                sampleWorldTicks();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "World tick sampling failed.", e);
            } finally {
                tickSampleRunning.set(false);
            }
        });
    }

    private void sampleWorldTicks() {
        int totalPlayers = 0;
        boolean playersKnown = false;
        long totalChunks = 0;
        boolean chunksKnown = false;
        for (sh.harold.blackbox.core.health.HealthSnapshot.World world : worldStats.worlds().worlds()) {
            if (world.players() >= 0) {
                totalPlayers += world.players();
                playersKnown = true;
            }
            if (world.chunks() >= 0) {
                totalChunks += world.chunks();
                chunksKnown = true;
            }
            if (world.tps() < 0) {
                continue;
            }
            BlackboxWorldTickEvent event = new BlackboxWorldTickEvent();
            event.world = world.name();
            event.tps = world.tps();
            event.mspt = world.mspt();
            event.players = world.players();
            event.entities = world.entities();
            event.avgPingMs = world.avgPingMs();
            event.commit();
        }
        if (chunksKnown) {
            BlackboxApi.recordGauge("Chunks loaded", totalChunks);
        }
        sampleDisk();
        PlayerDropDetector playerDrop = engine.playerDropDetector();
        if (playerDrop != null && playersKnown) {
            for (TriggerEvent event : playerDrop.sample(totalPlayers)) {
                capture(event);
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
    }

    private static final int SYSTEM_TICK_PER_WORLD_LIMIT = 4;

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

    /** Cumulative process read/write bytes from /proc/self/io; {-1, -1} where unavailable. */
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

    private void tickHeartbeats() {
        try {
            Universe universe = Universe.get();
            Map<String, World> worlds = universe.getWorlds();
            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                String scope = entry.getKey();
                if (scope == null || scope.isBlank()) {
                    continue;
                }
                World world = entry.getValue();
                if (world == null) {
                    continue;
                }

                AtomicBoolean pending = heartbeatPending.computeIfAbsent(scope, ignored -> new AtomicBoolean(false));
                if (!pending.compareAndSet(false, true)) {
                    continue;
                }

                try {
                    world.execute(() -> {
                        try {
                            heartbeatRegistry.beat(scope);
                        } catch (Exception e) {
                            logger.log(System.Logger.Level.WARNING, "Failed to beat heartbeat for " + scope, e);
                        } finally {
                            pending.set(false);
                        }
                    });
                } catch (Exception e) {
                    pending.set(false);
                    logger.log(System.Logger.Level.WARNING, "Failed to post heartbeat for " + scope, e);
                }
            }

            heartbeatRegistry.retain(worlds.keySet());

            heartbeatSweepCounter++;
            if (heartbeatSweepCounter >= 100) {
                heartbeatSweepCounter = 0;
                heartbeatPending.keySet().removeIf(scope -> !worlds.containsKey(scope));
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Heartbeat tick failed.", e);
        }
    }

    private void scheduleStallCheck() {
        if (!stallCheckRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
            try {
                Engine current = engine;
                for (TriggerEvent event : current.stallDetector().check()) {
                    capture(event);
                }
                for (TriggerEvent event : current.tickDegradedDetector().check()) {
                    capture(event);
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Stall check failed.", e);
            } finally {
                stallCheckRunning.set(false);
            }
        });
    }

    /**
     * Average tick length per world in milliseconds, read from the engine's tick-length
     * {@code HistoricMetric} (the same metric set {@link HytaleWorldStatsProvider} reads at capture
     * time). Period index 1 is the 1-minute window ({@code TickingThread} registers 10s/1m/5m); the
     * 1-minute average is used so a single long tick cannot trip the TICK_DEGRADED detector — only
     * sustained slowness moves it past the threshold.
     */
    private static final int TICK_AVG_PERIOD_INDEX = 1;

    private static Map<String, Double> tickAverageMillis() {
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

    /**
     * Blocks (on the capture worker, not the scheduler) until the stalled scope's heartbeat advances past
     * the trigger time, or until {@code maxWait} elapses. Returns immediately when disabled, when there is
     * no scope, or when the thread is interrupted (for example by shutdown).
     */
    private static void awaitHeartbeatRecovery(
        TriggerEvent event,
        HeartbeatRegistry registry,
        Clock clock,
        Duration maxWait,
        System.Logger logger
    ) {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return;
        }
        if (event.kind() != TriggerKind.HEARTBEAT_STALL) {
            return;
        }
        String scope = event.scope();
        if (scope == null || scope.isBlank()) {
            return;
        }
        Instant triggerAt = event.at();
        Instant deadline = clock.instant().plus(maxWait);
        while (clock.instant().isBefore(deadline)) {
            Instant last = registry.lastBeat(scope);
            if (last != null && last.isAfter(triggerAt)) {
                logger.log(System.Logger.Level.INFO,
                    "Heartbeat for '" + scope + "' resumed; dumping recording with post-incident recovery.");
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.log(System.Logger.Level.INFO,
            "Heartbeat for '" + scope + "' did not resume within " + maxWait.toSeconds()
            + "s; dumping the recording now.");
    }

    @Override
    public void close() {
        try {
            if (netStream != null) {
                netStream.close();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Network sampler shutdown failed.", e);
        }
        try {
            scheduler.shutdownNow();
            worker.shutdownNow();
            worker.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Executor shutdown failed.", e);
        }

        if (engine != null && !engine.config().jfrSnapshotInterval().isZero()) {
            try {
                if (capturePending.get()) {
                    Files.createDirectories(rollingFile.getParent());
                    jfr.dump(rollingFile);
                    logger.log(System.Logger.Level.WARNING,
                        "Shutdown during an in-flight capture; left a rolling snapshot at " + rollingFile
                        + " for recovery on the next startup.");
                } else {
                    Files.deleteIfExists(rollingFile);
                    Files.deleteIfExists(rollingFile.resolveSibling("rolling.jfr.tmp"));
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to finalize the rolling snapshot on shutdown.", e);
            }
        }

        try {
            jfr.close();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR shutdown failed.", e);
        }
    }
}
