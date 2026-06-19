package sh.harold.blackbox.hytale;

import com.hypixel.hytale.logger.sentry.SkipSentryException;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
    private volatile TextRedactor textRedactor;
    private final PlayerNameMasker nameMasker = new PlayerNameMasker();
    private HytaleLogWatcher logWatcher;

    private final AtomicBoolean stallCheckRunning = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRunning = new AtomicBoolean(false);
    private final AtomicBoolean tickSampleRunning = new AtomicBoolean(false);
    private final AtomicBoolean healthCheckRunning = new AtomicBoolean(false);
    private final HytaleWorldStatsProvider worldStats;

    private final AtomicReference<Instant> lastIncidentAt = new AtomicReference<>();
    private final AtomicReference<String> lastIncidentId = new AtomicReference<>();
    private final AtomicBoolean capturePending = new AtomicBoolean(false);

    private static final String FLOGGER_LOG_SITE_STACK_TRACE = "com.google.common.flogger.LogSiteStackTrace";
    private final Map<String, Instant> recentLogErrors = new ConcurrentHashMap<>();

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
        runtime.syncNetSampler();
        runtime.recoverOrphanedRecordings();
        runtime.startSnapshotWriter();
        runtime.startScheduledWork();
        runtime.registerCommands();
        runtime.registerWorldFailureListener();
        runtime.connectionTracker.register();
        runtime.registerLogWatcher();
        BlackboxApi.registerDiagnostics("Environment", HytaleEnvironment::snapshot);
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

    private void registerWorldFailureListener() {
        try {
            plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onRemoveWorld);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to RemoveWorldEvent; world-failure captures disabled.", e);
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
        if (thrown != null && FLOGGER_LOG_SITE_STACK_TRACE.equals(thrown.getClass().getName())) {
            thrown = thrown.getCause();
        }
        if (detectors.logErrorRequireThrowable() && thrown == null) {
            return;
        }
        if (detectors.logErrorSkipSentry() && SkipSentryException.hasSkipSentry(thrown)) {
            return;
        }
        Map<String, String> attrs = logErrorAttrs(record, thrown);
        if (isIgnoredLogError(detectors, attrs.get("error"))) {
            return;
        }
        if (isDuplicateLogError(detectors, logErrorSignature(record, thrown))) {
            return;
        }
        TriggerEvent event = new TriggerEvent(
            TriggerKind.LOG_ERROR, "server", clock.instant(), attrs);
        try {
            worker.execute(() -> capture(event));
        } catch (RejectedExecutionException e) {
        }
    }

    private static boolean isIgnoredLogError(DetectorPolicy detectors, String errorText) {
        List<Pattern> ignore = detectors.logErrorIgnore();
        if (ignore.isEmpty() || errorText == null || errorText.isEmpty()) {
            return false;
        }
        for (Pattern pattern : ignore) {
            if (pattern.matcher(errorText).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateLogError(DetectorPolicy detectors, String signature) {
        Duration window = detectors.logErrorDedupeWindow();
        if (window == null || window.isZero()) {
            return false;
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        recentLogErrors.values().removeIf(seen -> seen.isBefore(cutoff));
        return recentLogErrors.putIfAbsent(signature, now) != null;
    }

    private static String logErrorSignature(LogRecord record, Throwable thrown) {
        if (thrown != null) {
            StringBuilder sig = new StringBuilder(thrown.getClass().getName());
            String message = thrown.getMessage();
            if (message != null && !message.isBlank()) {
                sig.append(": ").append(message);
            }
            StackTraceElement[] frames = thrown.getStackTrace();
            if (frames.length > 0) {
                sig.append(" @ ").append(frames[0]);
            }
            return sig.toString();
        }
        String loggerName = record.getLoggerName();
        String message = record.getMessage();
        String firstLine = message == null ? "" : message.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .findFirst()
            .orElse("");
        return (loggerName == null || loggerName.isBlank() ? "log" : loggerName) + ": " + firstLine;
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

    private void syncNetSampler() {
        netSampler.sync(engine.netSaturationDetector() != null);
    }

    private void scheduleTickSample() {
        if (!tickSampleRunning.compareAndSet(false, true)) {
            return;
        }
        worker.execute(() -> {
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

    @Override
    public void close() {
        stopMetricsExporter();
        if (logWatcher != null) {
            try {
                logWatcher.unregister();
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Log watcher shutdown failed.", e);
            }
        }
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
