package sh.harold.blackbox.hytale;

import com.hypixel.hytale.logger.sentry.SkipSentryException;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupDisconnectEvent;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import jdk.jfr.consumer.RecordingStream;
import sh.harold.blackbox.core.bundle.BundleBuilder;
import sh.harold.blackbox.core.bundle.BundleExtrasRegistry;
import sh.harold.blackbox.core.capture.CapturePipeline;
import sh.harold.blackbox.core.capture.IncidentNotifier;
import sh.harold.blackbox.core.capture.PostIncidentWaiter;
import sh.harold.blackbox.core.capture.RecordingDumper;
import sh.harold.blackbox.core.config.BlackboxConfig;
import sh.harold.blackbox.core.jfr.BlackboxConnectionEvent;
import sh.harold.blackbox.core.jfr.BlackboxDiskEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginEvent;
import sh.harold.blackbox.core.health.HostCpuStats;
import sh.harold.blackbox.core.jfr.BlackboxHostCpuEvent;
import sh.harold.blackbox.core.jfr.BlackboxSystemTickEvent;
import sh.harold.blackbox.core.jfr.BlackboxWorldTickEvent;
import sh.harold.blackbox.core.jfr.JfrController;
import sh.harold.blackbox.core.jfr.JfrRepository;
import sh.harold.blackbox.core.metrics.MetricsLog;
import sh.harold.blackbox.core.notify.discord.DiscordWebhookNotifier;
import sh.harold.blackbox.core.notify.discord.HttpClientWebhookTransport;
import sh.harold.blackbox.core.retention.FileDeleter;
import sh.harold.blackbox.core.retention.RetentionManager;
import sh.harold.blackbox.core.trigger.DetectorPolicy;
import sh.harold.blackbox.core.trigger.ModulePolicy;
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
    private final Path sessionPointer;
    private volatile Path currentSessionDir;
    private final MetricsLog metricsLog;
    private long lastGcMillis = -1;
    private long lastGcWallMillis;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;

    private final JfrController jfr;
    private final HeartbeatRegistry heartbeatRegistry;
    private final BundleExtrasRegistry extrasRegistry;

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
    private HytaleLogWatcher logWatcher;

    private final AtomicBoolean stallCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRunning = new AtomicBoolean(false);
    private final AtomicBoolean tickSampleRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean profileActive = new AtomicBoolean(false);
    private volatile long profileEndsAtMs;
    private final HytaleWorldStatsProvider worldStats;

    private final Map<String, double[]> netRatesByInterface = new ConcurrentHashMap<>();
    private volatile RecordingStream netStream;
    private final Map<UUID, Long> joinStartNanos = new ConcurrentHashMap<>();
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
            () -> runtime.engine.config().capturePolicy().logTailLines(),
            dataDir.resolve("metrics")));
        runtime.syncNetSampler();
        runtime.recoverOrphanedRecordings();
        runtime.startSnapshotWriter();
        runtime.startScheduledWork();
        runtime.registerCommands();
        runtime.registerWorldFailureListener();
        runtime.registerConnectionListeners();
        runtime.registerLogWatcher();
        BlackboxApi.registerDiagnostics("Environment", HytaleEnvironment::snapshot);
        runtime.logStartup();
        return runtime;
    }

    private Engine buildEngine(BlackboxConfig config) {
        DetectorPolicy detectors = config.triggerPolicy().detectors();
        ModulePolicy modules = detectors.modules();

        HeartbeatStallDetector stallDetector = modules.heartbeatStall()
            ? new HeartbeatStallDetector(
                clock,
                heartbeatRegistry,
                config.triggerPolicy().stallDegradedMs())
            : null;
        TickDegradedDetector tickDegradedDetector = modules.tickDegraded()
            ? new TickDegradedDetector(
                clock,
                BlackboxRuntime::tickAverageMillis,
                config.triggerPolicy().tickAvgDegradedMs())
            : null;
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
            () -> HytaleAssetPacks.appendTo(HytaleServerSettings.appendTo(HytaleModConfigs.appendTo(HytaleMixins.appendTo(HytalePlugins.appendTo(
                HytaleEntities.appendTo(HytaleTickSystems.appendTo(HytaleServerLog.appendTo(
                    BlackboxApi.collectDiagnostics(), config.capturePolicy().logTailLines())))))))),
            postIncidentWaiter,
            incidentDir,
            tempDir,
            config.capturePolicy(),
            logger
        );

        return new Engine(
            config,
            stallDetector,
            tickDegradedDetector,
            capturePipeline,
            modules.deadlock()
                ? new DeadlockDetector(clock, BlackboxRuntime::deadlockedThreadIds)
                : null,
            modules.heapPressure() && detectors.heapPressurePct() > 0
                ? new HeapPressureDetector(clock, BlackboxRuntime::tenuredAfterGcFraction,
                    detectors.heapPressurePct() / 100.0, detectors.heapPressureSustain())
                : null,
            modules.gcPressure() && detectors.gcPressurePct() > 0 && !detectors.gcPressureWindow().isZero()
                ? new GcPressureDetector(clock, BlackboxRuntime::totalGcTimeMillis,
                    detectors.gcPressurePct() / 100.0, detectors.gcPressureWindow())
                : null,
            modules.cpuSaturation() && detectors.cpuSaturationPct() > 0
                ? new CpuSaturationDetector(clock, BlackboxRuntime::processCpuLoad,
                    detectors.cpuSaturationPct() / 100.0, detectors.cpuSaturationSustain())
                : null,
            modules.netSaturation() && (detectors.netInMbps() > 0 || detectors.netOutMbps() > 0)
                ? new NetSaturationDetector(clock, this::latestNetRates,
                    detectors.netInMbps(), detectors.netOutMbps(), detectors.netSustain())
                : null,
            modules.playerDrop() && detectors.playerDropPct() > 0 && !detectors.playerDropWindow().isZero()
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
        this.sessionPointer = dataDir.resolve("live").resolve("jfr-session");
        this.metricsLog = new MetricsLog(dataDir.resolve("metrics"));
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

    private void registerConnectionListeners() {
        try {
            plugin.getEventRegistry().registerGlobal(PlayerSetupConnectEvent.class,
                e -> {
                    markJoinStart(e.getUuid());
                    commitConnectionEvent(e.getUsername(), "connect", null);
                });
            plugin.getEventRegistry().registerGlobal(PlayerSetupDisconnectEvent.class,
                e -> {
                    joinStartNanos.remove(e.getUuid());
                    commitConnectionEvent(e.getUsername(), "setup disconnect",
                        String.valueOf(e.getDisconnectReason()));
                });
            plugin.getEventRegistry().registerGlobal(PlayerConnectEvent.class,
                e -> {
                    var ref = e.getPlayerRef();
                    String world = e.getWorld() == null ? null : e.getWorld().getName();
                    String dur = joinDuration(ref == null ? null : ref.getUuid());
                    String detail = world == null ? dur : (dur == null ? world : world + " · " + dur);
                    commitConnectionEvent(ref == null ? null : ref.getUsername(), "join", detail);
                });
            plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,
                e -> commitConnectionEvent(e.getPlayerRef() == null ? null : e.getPlayerRef().getUsername(),
                    "leave", disconnectReason(e)));
            plugin.getEventRegistry().registerGlobal(ShutdownEvent.class,
                e -> commitServerMarker("shutdown initiated"));
        } catch (Throwable t) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to player connection events; connection timeline disabled.", t);
        }
    }

    private void markJoinStart(UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (joinStartNanos.size() > 512) {
            joinStartNanos.clear();
        }
        joinStartNanos.put(uuid, System.nanoTime());
    }

    private String joinDuration(UUID uuid) {
        Long start = uuid == null ? null : joinStartNanos.remove(uuid);
        if (start == null) {
            return null;
        }
        return String.format(Locale.ROOT, "joined in %.1fs", (System.nanoTime() - start) / 1e9);
    }

    private void commitServerMarker(String message) {
        try {
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = "Server";
            event.message = message;
            event.commit();
        } catch (Throwable t) {
            logger.log(System.Logger.Level.DEBUG, "Failed to commit server marker.", t);
        }
    }

    private static String disconnectReason(PlayerDisconnectEvent event) {
        try {
            Object reason = event.getDisconnectReason();
            return reason == null ? null : reason.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void commitConnectionEvent(String player, String phase, String detail) {
        try {
            BlackboxConnectionEvent event = new BlackboxConnectionEvent();
            event.player = player == null || player.isBlank() ? "unknown" : player;
            event.phase = phase;
            event.detail = detail;
            event.commit();
        } catch (Throwable t) {
            logger.log(System.Logger.Level.DEBUG, "Failed to commit connection event.", t);
        }
    }

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

    private void registerLogWatcher() {
        try {
            HytaleLogWatcher watcher = new HytaleLogWatcher(this::onSevereLog);
            watcher.register();
            this.logWatcher = watcher;
        } catch (Throwable t) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to the server log; log-error captures disabled.", t);
        }
    }

    private void onSevereLog(LogRecord record) {
        Engine current = engine;
        DetectorPolicy detectors = current == null
            ? null : current.config().triggerPolicy().detectors();
        if (detectors == null || !detectors.modules().logError()) {
            return;
        }
        Throwable thrown = record.getThrown();
        if (detectors.logErrorRequireThrowable() && thrown == null) {
            return;
        }
        if (detectors.logErrorSkipSentry() && SkipSentryException.hasSkipSentry(thrown)) {
            return;
        }
        TriggerEvent event = new TriggerEvent(
            TriggerKind.LOG_ERROR, logErrorScope(record, thrown), clock.instant(), logErrorAttrs(record, thrown));
        try {
            worker.execute(() -> capture(event));
        } catch (RejectedExecutionException e) {
        }
    }

    private static String logErrorScope(LogRecord record, Throwable thrown) {
        if (thrown != null) {
            String simple = thrown.getClass().getSimpleName();
            if (simple != null && !simple.isBlank()) {
                return simple;
            }
        }
        String loggerName = record.getLoggerName();
        return loggerName == null || loggerName.isBlank() ? "log" : loggerName;
    }

    private static Map<String, String> logErrorAttrs(LogRecord record, Throwable thrown) {
        Map<String, String> attrs = failureAttrs(thrown);
        if (!attrs.isEmpty()) {
            return attrs;
        }
        String message = record.getMessage();
        if (message == null) {
            return Map.of();
        }
        String firstLine = message.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .findFirst()
            .orElse("");
        if (firstLine.isEmpty()) {
            return Map.of();
        }
        String error = firstLine.length() > 200 ? firstLine.substring(0, 200) + "…" : firstLine;
        return Map.of("error", error);
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

    String startProfileSession(int minutes) {
        int clamped = Math.max(1, Math.min(30, minutes));
        if (!profileActive.compareAndSet(false, true)) {
            return "A profile session is already running (" + profileRemainingSeconds() + "s remaining).";
        }
        try {
            capture(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                Map.of("reason", "profile lead-up")));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to capture the pre-profile lead-up.", e);
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

    private void recoverOrphanedRecordings() {
        String previousSession = readSessionPointer();
        locateCurrentSession();
        persistCurrentSession();
        boolean snapshots = !engine.config().jfrSnapshotInterval().isZero();
        worker.execute(() -> {
            try {
                Files.createDirectories(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to create the recovery directory.", e);
            }
            boolean harvested = harvestPreviousRepository(previousSession);
            if (!harvested && snapshots) {
                try {
                    if (Files.exists(rollingFile)) {
                        Path staged = recoverDir.resolve("rolling-" + clock.millis() + ".jfr");
                        Files.move(rollingFile, staged, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to stage an orphaned recording for recovery.", e);
                }
            }
            try {
                engine.capturePipeline().recoverOrphans(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery scan failed.", e);
            }
        });
    }

    private String readSessionPointer() {
        try {
            if (Files.exists(sessionPointer)) {
                return Files.readString(sessionPointer).trim();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to read the JFR repository pointer.", e);
        }
        return "";
    }

    private void locateCurrentSession() {
        try {
            Path base = JfrRepository.repositoryBase(
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Path.of(System.getProperty("java.io.tmpdir", ".")));
            currentSessionDir = JfrRepository.sessionDirForPid(base, ProcessHandle.current().pid()).orElse(null);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to locate the active JFR repository.", e);
        }
    }

    private void persistCurrentSession() {
        if (currentSessionDir == null) {
            return;
        }
        try {
            Files.createDirectories(sessionPointer.getParent());
            Files.writeString(sessionPointer, currentSessionDir.toString());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to record the JFR repository location.", e);
        }
    }

    private boolean harvestPreviousRepository(String previousSession) {
        if (previousSession == null || previousSession.isEmpty()) {
            return false;
        }
        Path previousDir = Path.of(previousSession);
        if (previousDir.equals(currentSessionDir)) {
            return false;
        }
        List<Path> chunks;
        try {
            chunks = JfrRepository.chunkFiles(previousDir);
        } catch (Exception e) {
            return false;
        }
        if (chunks.isEmpty()) {
            return false;
        }
        Path merged = recoverDir.resolve("harvested-" + clock.millis() + ".jfr");
        boolean recovered = false;
        try {
            if (JfrRepository.merge(chunks, merged)) {
                recovered = engine.capturePipeline()
                    .recoverFromRecording(merged, lastModifiedOrNow(previousDir))
                    .isPresent();
                if (recovered) {
                    logger.log(System.Logger.Level.INFO,
                        "Recovered an incident bundle from " + chunks.size()
                        + " JFR repository chunk(s) of a crashed run.");
                }
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to harvest the previous JFR repository.", e);
        } finally {
            try {
                Files.deleteIfExists(merged);
            } catch (Exception ignored) {
            }
            deleteRecursively(previousDir);
        }
        return recovered;
    }

    private Instant lastModifiedOrNow(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            return clock.instant();
        }
    }

    private void recordHealthMetrics(double tickAvgMs, double tps, int players) {
        BlackboxConfig cfg = engine.config();
        if (!cfg.metricsEnabled()) {
            return;
        }
        try {
            Instant now = clock.instant();
            String row = MetricsLog.formatRow(now, tickAvgMs, tps, players,
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                readRssBytes(), gcFractionPercent());
            metricsLog.append(now, row, cfg.metricsRetentionDays());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to append health metrics.", e);
        }
    }

    private double gcFractionPercent() {
        long gcNow = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                gcNow += time;
            }
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

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (java.nio.file.NoSuchFileException e) {
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to clean up harvested repository " + dir + ".", e);
        }
    }

    private void startSnapshotWriter() {
        Duration interval = engine.config().jfrSnapshotInterval();
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
        double worstTps = -1;
        double worstMspt = -1;
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
            event.avgPingMs = world.avgPingMs();
            event.commit();
        }
        if (chunksKnown) {
            BlackboxApi.recordGauge("Chunks loaded", totalChunks);
        }
        recordHealthMetrics(worstMspt, worstTps, playersKnown ? totalPlayers : -1);
        sampleDisk();
        sampleHostCpu();
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
                HeartbeatStallDetector stall = current.stallDetector();
                if (stall != null) {
                    for (TriggerEvent event : stall.check()) {
                        capture(event);
                    }
                }
                TickDegradedDetector tick = current.tickDegradedDetector();
                if (tick != null) {
                    for (TriggerEvent event : tick.check()) {
                        capture(event);
                    }
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Stall check failed.", e);
            } finally {
                stallCheckRunning.set(false);
            }
        });
    }

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
        if (logWatcher != null) {
            try {
                logWatcher.unregister();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Log watcher shutdown failed.", e);
            }
        }
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
