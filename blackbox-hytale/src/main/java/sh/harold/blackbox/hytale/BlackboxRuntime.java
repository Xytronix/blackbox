package sh.harold.blackbox.hytale;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import sh.harold.blackbox.core.bundle.BundleBuilder;
import sh.harold.blackbox.core.bundle.BundleExtrasRegistry;
import sh.harold.blackbox.core.capture.CapturePipeline;
import sh.harold.blackbox.core.capture.IncidentNotifier;
import sh.harold.blackbox.core.capture.PostIncidentWaiter;
import sh.harold.blackbox.core.capture.RecordingDumper;
import sh.harold.blackbox.core.config.BlackboxConfig;
import sh.harold.blackbox.core.env.TextRedactor;
import sh.harold.blackbox.core.incident.IncidentMetadata;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.jfr.JfrController;
import sh.harold.blackbox.core.metrics.HealthGauges;
import sh.harold.blackbox.core.metrics.MetricsLog;
import sh.harold.blackbox.core.metrics.PrometheusExporter;
import sh.harold.blackbox.core.report.TrendReport;
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
    private static final DateTimeFormatter INCIDENT_TS =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss.SSSZ").withLocale(Locale.ROOT);
    private final BlackboxPlugin plugin;
    private final Clock clock;
    private final System.Logger logger;
    private final Path dataDir;
    private final Path configPath;
    private final Path incidentDir;
    private final Path tempDir;
    private final Path rollingFile;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;

    private final JfrController jfr;
    private final HeartbeatRegistry heartbeatRegistry;
    private final HytaleHeartbeatPump heartbeatPump;
    private final BundleExtrasRegistry extrasRegistry;
    private final HytaleConnectionTracker connectionTracker;
    private final JfrSessionRecovery sessionRecovery;
    private final ProfileSessionController profileSessions;
    private final HytaleNetSampler netSampler;
    private final HealthGauges healthGauges = new HealthGauges();
    private final HytaleTelemetrySampler telemetry;
    private volatile PrometheusExporter prometheusExporter;

    record Engine(
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
    private volatile TextRedactor textRedactor;
    private final PlayerNameMasker nameMasker = new PlayerNameMasker();
    private final HytaleLogErrorWatcher logErrorWatcher;
    private final HytaleWorldFailureListener worldFailureListener;
    private final HytaleHealthScheduler healthScheduler;

    private final HytaleWorldStatsProvider worldStats;

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
            config.jfrConfiguration(), config.jfrOldObjectSampling()
        );
        jfr.start();

        BlackboxRuntime runtime = new BlackboxRuntime(plugin, clock, logger, dataDir, scheduler, worker, jfr);
        runtime.engine = runtime.buildEngine(config);
        runtime.extrasRegistry.register(new HytaleBundleExtrasProvider(
            runtime.heartbeatRegistry,
            () -> runtime.engine.config().capturePolicy().logTailLines(),
            () -> runtime.engine.config().capturePolicy().includeServerLog(),
            dataDir.resolve("metrics"),
            runtime.nameMasker));
        runtime.healthScheduler.syncNetSampler();
        runtime.recoverOrphanedRecordings();
        runtime.healthScheduler.startSnapshotWriter();
        runtime.healthScheduler.start();
        runtime.registerCommands();
        runtime.worldFailureListener.register();
        runtime.connectionTracker.register();
        runtime.logErrorWatcher.register();
        BlackboxApi.registerDiagnostics("blackbox", "Environment", HytaleEnvironment::snapshot);
        runtime.refreshBundleStats();
        runtime.startMetricsExporter();
        runtime.logStartup();
        return runtime;
    }

    private Engine buildEngine(BlackboxConfig config) {
        this.textRedactor = new TextRedactor(config.capturePolicy().redactPatterns());
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
                HytaleHealthScheduler::tickAverageMillis,
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
            HytaleHeartbeatPump.awaitRecovery(event, heartbeatRegistry, clock, config.postIncidentMaxWait(), logger);
        };
        IncidentNotifier configuredNotifier = buildNotifier(clock, config, logger, worker);
        IncidentNotifier notifier = (report, zip) -> {
            recordIncidentGauge(report);
            configuredNotifier.onIncident(report, zip);
        };

        CapturePipeline capturePipeline = new CapturePipeline(
            clock,
            triggerEngine,
            dumper,
            new BundleBuilder(clock, logger, textRedactor, nameMasker::maskText),
            new RetentionManager(clock, logger, FileDeleter.defaultDeleter()),
            notifier,
            extrasRegistry,
            worldStats,
            () -> HytaleAssetPacks.appendTo(HytaleServerSettings.appendTo(HytaleModConfigs.appendTo(HytaleMixins.appendTo(HytalePlugins.appendTo(
                HytaleEntities.appendTo(HytaleTickSystems.appendTo(HytaleHeartbeats.appendTo(HytaleServerLog.appendTo(
                    BlackboxApi.collectDiagnostics(), config.capturePolicy().includeServerLog(),
                    config.capturePolicy().logTailLines(), nameMasker,
                    config.capturePolicy().sanitizeLog()), heartbeatRegistry))))),
                config.capturePolicy().includeModConfigs(), BlackboxApi.registeredConfigPaths()), config.capturePolicy().includeServerConfig())),
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
                ? new DeadlockDetector(clock, JvmProbes::deadlockedThreadIds)
                : null,
            modules.heapPressure() && detectors.heapPressurePct() > 0
                ? new HeapPressureDetector(clock, JvmProbes::tenuredAfterGcFraction,
                    detectors.heapPressurePct() / 100.0, detectors.heapPressureSustain())
                : null,
            modules.gcPressure() && detectors.gcPressurePct() > 0 && !detectors.gcPressureWindow().isZero()
                ? new GcPressureDetector(clock, JvmProbes::stwGcPauseMillis,
                    detectors.gcPressurePct() / 100.0, detectors.gcPressureWindow())
                : null,
            modules.cpuSaturation() && detectors.cpuSaturationPct() > 0
                ? new CpuSaturationDetector(clock, JvmProbes::processCpuLoad,
                    detectors.cpuSaturationPct() / 100.0, detectors.cpuSaturationSustain())
                : null,
            modules.netSaturation() && (detectors.netInMbps() > 0 || detectors.netOutMbps() > 0)
                ? new NetSaturationDetector(clock, netSampler::latestRates,
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

    private void recordIncidentGauge(IncidentReport report) {
        try {
            IncidentMetadata meta = report.meta();
            healthGauges.incrementIncident(meta.trigger(),
                meta.severity().name().toLowerCase(Locale.ROOT), meta.createdAt().getEpochSecond());
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to record incident gauge.", e);
        }
        refreshBundleStats();
    }

    private void refreshBundleStats() {
        try (var entries = Files.list(incidentDir)) {
            long[] acc = {0L, 0L};
            entries.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .forEach(p -> {
                    try {
                        acc[0]++;
                        acc[1] += Files.size(p);
                    } catch (IOException ignored) {
                    }
                });
            healthGauges.setBundles(acc[0], acc[1]);
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to refresh bundle stats.", e);
        }
    }

    private void startMetricsExporter() {
        BlackboxConfig cfg = engine.config();
        if (!cfg.prometheusEnabled()) {
            return;
        }
        try {
            prometheusExporter = new PrometheusExporter(
                healthGauges, cfg.prometheusBind(), cfg.prometheusPort(), "/metrics");
            logger.log(System.Logger.Level.INFO, "Prometheus metrics on http://"
                + cfg.prometheusBind() + ":" + prometheusExporter.port() + "/metrics");
        } catch (IOException e) {
            logger.log(System.Logger.Level.WARNING, "Failed to start Prometheus metrics endpoint.", e);
        }
    }

    private void stopMetricsExporter() {
        PrometheusExporter exporter = prometheusExporter;
        if (exporter != null) {
            try {
                exporter.close();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to stop Prometheus metrics endpoint.", e);
            }
            prometheusExporter = null;
        }
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
        this.scheduler = scheduler;
        this.worker = worker;
        this.jfr = jfr;
        this.heartbeatRegistry = new HeartbeatRegistry(clock);
        this.heartbeatPump = new HytaleHeartbeatPump(logger, heartbeatRegistry);
        this.extrasRegistry = new BundleExtrasRegistry(logger);
        this.worldStats = new HytaleWorldStatsProvider(nameMasker);
        this.connectionTracker = new HytaleConnectionTracker(plugin, logger, nameMasker);
        this.sessionRecovery = new JfrSessionRecovery(clock, logger, rollingFile,
            dataDir.resolve("live").resolve("recover"), dataDir.resolve("live").resolve("jfr-session"));
        this.profileSessions = new ProfileSessionController(clock, logger, scheduler, worker, jfr, this::capture);
        this.netSampler = new HytaleNetSampler(clock, logger);
        this.telemetry = new HytaleTelemetrySampler(clock, logger, worldStats,
            new MetricsLog(dataDir.resolve("metrics")), healthGauges,
            () -> engine.config(), () -> engine.playerDropDetector(), this::capture);
        this.logErrorWatcher = new HytaleLogErrorWatcher(this, clock, logger,
            () -> { Engine e = engine; return e == null ? null : e.config().triggerPolicy().detectors(); });
        this.worldFailureListener = new HytaleWorldFailureListener(this, plugin, clock, logger);
        this.healthScheduler = new HytaleHealthScheduler(this, logger, scheduler, heartbeatPump,
            telemetry, netSampler, jfr, rollingFile, () -> engine);
        Package pkg = BlackboxRuntime.class.getPackage();
        healthGauges.setVersion(pkg == null ? null : pkg.getImplementationVersion());
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

    Optional<String> captureManual() {
        TriggerEvent event = new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(), java.util.Map.of());
        return capture(event);
    }

    Optional<String> capture(TriggerEvent event) {
        event = maskAttrs(event);
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

    private TriggerEvent maskAttrs(TriggerEvent event) {
        if (event.attrs().isEmpty()) {
            return event;
        }
        Map<String, String> masked = new HashMap<>();
        event.attrs().forEach((key, value) -> masked.put(key, nameMasker.maskText(value)));
        return new TriggerEvent(event.kind(), event.scope(), event.at(), masked);
    }

    String maskText(String text) {
        return nameMasker.maskText(text);
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
        healthScheduler.syncNetSampler();

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
        if (previous.prometheusEnabled() != fresh.prometheusEnabled()
            || previous.prometheusPort() != fresh.prometheusPort()
            || !previous.prometheusBind().equals(fresh.prometheusBind())) {
            stopMetricsExporter();
            startMetricsExporter();
            changed.add("Metrics.Prometheus");
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

    String startProfileSession(int minutes, boolean keepBuffer) {
        return profileSessions.start(minutes, keepBuffer);
    }

    Path generateTrendReport(int days) throws IOException {
        Instant now = clock.instant();
        MetricsLog.Trend trend = new MetricsLog(dataDir.resolve("metrics")).read(now, days);
        if (trend.isEmpty()) {
            return null;
        }
        long fromMs = now.minus(Duration.ofDays(days)).toEpochMilli();
        long[] incidents = incidentTimesWithin(fromMs, now.toEpochMilli());
        Path out = dataDir.resolve("trend-report.html");
        try (OutputStream os = Files.newOutputStream(out)) {
            TrendReport.write(trend, days, now, incidents, os);
        }
        return out;
    }

    private long[] incidentTimesWithin(long fromMs, long toMs) {
        if (!Files.isDirectory(incidentDir)) {
            return new long[0];
        }
        List<Long> times = new ArrayList<>();
        try (Stream<Path> entries = Files.list(incidentDir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(p -> {
                String base = p.getFileName().toString();
                base = base.substring(0, base.length() - ".zip".length());
                if (base.startsWith("incident-")) {
                    base = base.substring("incident-".length());
                }
                int lastDash = base.lastIndexOf('-');
                if (lastDash <= 0) {
                    return;
                }
                try {
                    long ms = OffsetDateTime.parse(base.substring(0, lastDash), INCIDENT_TS)
                        .toInstant().toEpochMilli();
                    if (ms >= fromMs && ms <= toMs) {
                        times.add(ms);
                    }
                } catch (RuntimeException ignored) {
                }
            });
        } catch (IOException e) {
            return new long[0];
        }
        times.sort(null);
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = times.get(i);
        }
        return out;
    }

    boolean profileActive() {
        return profileSessions.active();
    }

    long profileRemainingSeconds() {
        return profileSessions.remainingSeconds();
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
        sessionRecovery.recoverOrphanedRecordings(
            !engine.config().jfrSnapshotInterval().isZero(),
            () -> engine.capturePipeline(),
            worker);
    }

    @Override
    public void close() {
        stopMetricsExporter();
        logErrorWatcher.unregister();
        netSampler.close();
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
