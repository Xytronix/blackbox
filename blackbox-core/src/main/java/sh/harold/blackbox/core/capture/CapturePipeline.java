package sh.harold.blackbox.core.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import sh.harold.blackbox.core.bundle.BundleArtifacts;
import sh.harold.blackbox.core.bundle.BundleAttachment;
import sh.harold.blackbox.core.bundle.BundleBuilder;
import sh.harold.blackbox.core.env.TextRedactor;
import sh.harold.blackbox.core.health.HealthCollector;
import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.health.JfrHotThreads;
import sh.harold.blackbox.core.health.JfrSnapshot;
import sh.harold.blackbox.core.health.JfrThreadDump;
import sh.harold.blackbox.core.health.StalledThread;
import sh.harold.blackbox.core.health.WorldStatsProvider;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.incident.IncidentId;
import sh.harold.blackbox.core.incident.IncidentIds;
import sh.harold.blackbox.core.incident.IncidentMetadata;
import sh.harold.blackbox.core.incident.IncidentReport;
import sh.harold.blackbox.core.incident.IncidentSummary;
import sh.harold.blackbox.core.incident.Severity;
import sh.harold.blackbox.core.jfr.JfrRepository;
import sh.harold.blackbox.core.retention.RetentionManager;
import sh.harold.blackbox.core.trigger.TriggerDecision;
import sh.harold.blackbox.core.trigger.TriggerEngine;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerResult;

/**
 * Orchestrates trigger evaluation through capture and retention.
 */
public final class CapturePipeline {
    private static final String FAILED_DIR_NAME = "failed";

    private final Clock clock;
    private final TriggerEngine triggerEngine;
    private final RecordingDumper dumper;
    private final BundleBuilder bundleBuilder;
    private final RetentionManager retentionManager;
    private final IncidentNotifier notifier;
    private final BundleExtrasProvider extrasProvider;
    private final Path incidentDir;
    private final Path tempDir;
    private final CapturePolicy policy;
    private final System.Logger logger;
    private final TextRedactor redactor;
    private final WorldStatsProvider worldStatsProvider;
    private final DiagnosticsProvider diagnosticsProvider;
    private final PostIncidentWaiter postIncidentWaiter;

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(
            clock,
            triggerEngine,
            dumper,
            bundleBuilder,
            retentionManager,
            notifier,
            BundleExtrasProvider.none(),
            incidentDir,
            tempDir,
            policy,
            logger
        );
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(clock, triggerEngine, dumper, bundleBuilder, retentionManager, notifier, extrasProvider,
            WorldStatsProvider.none(), incidentDir, tempDir, policy, logger);
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        WorldStatsProvider worldStatsProvider,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this(clock, triggerEngine, dumper, bundleBuilder, retentionManager, notifier, extrasProvider,
            worldStatsProvider, DiagnosticsProvider.none(),
            PostIncidentWaiter.none(), incidentDir, tempDir, policy, logger);
    }

    public CapturePipeline(
        Clock clock,
        TriggerEngine triggerEngine,
        RecordingDumper dumper,
        BundleBuilder bundleBuilder,
        RetentionManager retentionManager,
        IncidentNotifier notifier,
        BundleExtrasProvider extrasProvider,
        WorldStatsProvider worldStatsProvider,
        DiagnosticsProvider diagnosticsProvider,
        PostIncidentWaiter postIncidentWaiter,
        Path incidentDir,
        Path tempDir,
        CapturePolicy policy,
        System.Logger logger
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.triggerEngine = Objects.requireNonNull(triggerEngine, "triggerEngine");
        this.dumper = Objects.requireNonNull(dumper, "dumper");
        this.bundleBuilder = Objects.requireNonNull(bundleBuilder, "bundleBuilder");
        this.retentionManager = Objects.requireNonNull(retentionManager, "retentionManager");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.extrasProvider = Objects.requireNonNull(extrasProvider, "extrasProvider");
        this.worldStatsProvider = Objects.requireNonNull(worldStatsProvider, "worldStatsProvider");
        this.diagnosticsProvider = Objects.requireNonNull(diagnosticsProvider, "diagnosticsProvider");
        this.postIncidentWaiter = Objects.requireNonNull(postIncidentWaiter, "postIncidentWaiter");
        this.incidentDir = Objects.requireNonNull(incidentDir, "incidentDir");
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.redactor = new TextRedactor(policy.redactPatterns());
    }

    public Optional<IncidentId> handle(TriggerEvent event) {
        if (!policy.enabled()) {
            return Optional.empty();
        }
        try {
            TriggerResult result = triggerEngine.evaluate(event);
            if (result.decision() != TriggerDecision.ACCEPT) {
                return Optional.empty();
            }

            IncidentId id = IncidentIds.next(clock);
            Instant createdAt = event.at();

            boolean wantReport = policy.artifacts().contains(BundleArtifacts.REPORT);
            HealthSnapshot snapshot = wantReport ? captureSnapshot() : null;

            Files.createDirectories(tempDir);
            Files.createDirectories(incidentDir);

            Path tempRecording = tempDir.resolve(id.value() + ".jfr");
            if (result.severity() != Severity.CRITICAL) {
                postIncidentWaiter.awaitResolution(event);
            }
            Path dumpedRecording = dumper.dump(tempRecording);

            if (wantReport) {
                snapshot = withHotThreads(snapshot, dumpedRecording);
            }
            List<DiagnosticSection> diagnostics = wantReport
                ? withModContribution(safeDiagnostics(), dumpedRecording)
                : List.of();
            if (wantReport) {
                diagnostics = StalledThread.prependTo(diagnostics, event, policy.frameworkPrefixes());
                diagnostics = MemoryPools.appendTo(diagnostics);
            }
            if (wantReport && policy.heapHistogram()) {
                diagnostics = HeapHistogram.appendTo(diagnostics);
            }
            if (wantReport && redactor.hasPatterns()) {
                diagnostics = redactDiagnostics(diagnostics);
            }
            IncidentReport report = buildReport(id, createdAt, result, event, snapshot, diagnostics);

            Path outputZip = incidentDir.resolve("incident-" + id.value() + ".zip");

            List<BundleAttachment> extras = List.of();
            try {
                extras = extrasProvider.extras(report, event);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Bundle extras provider failed.", e);
            }

            if (redactor.hasPatterns()) {
                extras = redactExtras(extras);
            }

            bundleBuilder.build(report, dumpedRecording, outputZip, extras, policy.artifacts());

            try {
                retentionManager.enforce(incidentDir, policy.retention());
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Retention enforcement failed.", e);
            }

            try {
                notifier.onIncident(report, outputZip);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Incident notification failed.", e);
            }

            try {
                Files.deleteIfExists(dumpedRecording);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to clean up temp recording.", e);
            }

            return Optional.of(id);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Capture pipeline failed.", e);
            return Optional.empty();
        }
    }

    public Optional<IncidentId> recoverFromRecording(Path recording, Instant occurredAt) {
        Objects.requireNonNull(recording, "recording");
        if (!policy.enabled()) {
            return Optional.empty();
        }
        try {
            try {
                JfrRepository.finalizeOrphan(recording);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING,
                    "Failed to finalize recovered recording " + recording + "; it may be unreadable.", e);
            }
            IncidentId id = IncidentIds.next(clock);
            Instant when = occurredAt != null ? occurredAt : clock.instant();
            boolean wantReport = policy.artifacts().contains(BundleArtifacts.REPORT);
            HealthSnapshot snapshot = wantReport ? JfrSnapshot.parse(recording, 1000) : null;
            List<DiagnosticSection> diagnostics = wantReport
                ? withModContribution(safeDiagnostics(), recording) : List.of();
            String threadDump = wantReport ? JfrThreadDump.lastDump(recording) : null;
            if (wantReport && threadDump != null) {
                DiagnosticSection stuck = JfrThreadDump.stuckSection(threadDump, policy.frameworkPrefixes());
                if (stuck != null) {
                    List<DiagnosticSection> withStuck = new ArrayList<>(diagnostics.size() + 1);
                    withStuck.add(stuck);
                    withStuck.addAll(diagnostics);
                    diagnostics = withStuck;
                }
            }
            if (wantReport && redactor.hasPatterns()) {
                diagnostics = redactDiagnostics(diagnostics);
            }
            IncidentMetadata meta = new IncidentMetadata(
                id, when, Severity.DEGRADED, "UNCLEAN_SHUTDOWN", null, "Recovered after an unclean shutdown");
            IncidentSummary summary = new IncidentSummary(
                "Unknown; the previous run did not shut down cleanly",
                List.of("The JVM exited without a clean shutdown; this bundle was rebuilt on the next startup from the "
                    + "rolling recording. The snapshot below is reconstructed from the recording's last samples."),
                List.of("Open recording.jfr in JDK Mission Control for the timeline leading up to the exit."));
            IncidentReport report = new IncidentReport(
                meta, summary, Map.of("recovered", "true"), snapshot, diagnostics);
            Files.createDirectories(incidentDir);
            Path outputZip = incidentDir.resolve("incident-" + id.value() + ".zip");
            List<BundleAttachment> extras = new ArrayList<>(extrasProvider.historicalExtras());
            extras.addAll(extrasProvider.configExtras());
            if (threadDump != null) {
                extras.add(new BundleAttachment("extras/threads.txt",
                    threadDump.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            if (redactor.hasPatterns()) {
                extras = redactExtras(extras);
            }
            bundleBuilder.build(report, recording, outputZip, extras, policy.artifacts());
            try {
                retentionManager.enforce(incidentDir, policy.retention());
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Retention enforcement failed.", e);
            }
            try {
                notifier.onIncident(report, outputZip);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery notification failed.", e);
            }
            logger.log(System.Logger.Level.INFO, "Rebuilt incident bundle " + id.value() + " from a recovered recording.");
            return Optional.of(id);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to rebuild bundle from recovered recording " + recording + ".", e);
            return Optional.empty();
        }
    }

    public List<IncidentId> recoverOrphans(Path dir) {
        Objects.requireNonNull(dir, "dir");
        List<IncidentId> recovered = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return recovered;
        }
        List<Path> recordings;
        try (Stream<Path> stream = Files.list(dir)) {
            recordings = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                .sorted()
                .toList();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to scan " + dir + " for recovered recordings.", e);
            return recovered;
        }
        for (Path recording : recordings) {
            Optional<IncidentId> id = recoverFromRecording(recording, lastModified(recording));
            if (id.isPresent()) {
                recovered.add(id.get());
                try {
                    Files.deleteIfExists(recording);
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to delete recovered recording " + recording + ".", e);
                }
            } else {
                setAsideFailedRecording(dir, recording);
            }
        }
        pruneFailedRecordings(dir.resolve(FAILED_DIR_NAME));
        return recovered;
    }

    private void setAsideFailedRecording(Path dir, Path recording) {
        try {
            Path failedDir = dir.resolve(FAILED_DIR_NAME);
            Files.createDirectories(failedDir);
            Files.move(recording, failedDir.resolve(recording.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);
            logger.log(System.Logger.Level.WARNING,
                "Could not rebuild a bundle from " + recording + "; moved it to " + failedDir
                    + " for inspection. It may still open in JDK Mission Control.");
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to set aside unrecoverable recording " + recording + ".", e);
        }
    }

    private void pruneFailedRecordings(Path failedDir) {
        if (!Files.isDirectory(failedDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(failedDir)) {
            List<Path> files = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                .sorted(Comparator.comparing(this::lastModified).reversed())
                .toList();
            for (int i = policy.retention().failedRecordingMaxCount(); i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to prune " + failedDir + ".", e);
        }
    }

    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (Exception e) {
            return clock.instant();
        }
    }

    private List<DiagnosticSection> safeDiagnostics() {
        try {
            return diagnosticsProvider.sections();
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Diagnostics provider failed.", e);
            return List.of();
        }
    }

    private IncidentReport buildReport(
        IncidentId id,
        Instant createdAt,
        TriggerResult result,
        TriggerEvent event,
        HealthSnapshot snapshot,
        List<DiagnosticSection> diagnostics
    ) {
        Map<String, String> context = event.attrs();
        if (redactor.hasPatterns()) {
            context = redactContext(context);
        }

        String headline = redactor.hasPatterns() ? redactor.redact(result.headline()) : result.headline();
        IncidentMetadata meta = new IncidentMetadata(
            id,
            createdAt,
            result.severity(),
            event.kind().name(),
            event.scope(),
            headline
        );

        String error = context.get("error");
        boolean hasError = error != null && !error.isBlank();
        List<String> whatHappened = new ArrayList<>();
        whatHappened.add("Triggered by " + event.kind().name());
        if (hasError) {
            whatHappened.add(error);
        }
        IncidentSummary summary = new IncidentSummary(
            hasError ? error : "Unknown",
            whatHappened,
            List.of("Review the incident report and recording.")
        );
        return new IncidentReport(meta, summary, context, snapshot, diagnostics);
    }

    private HealthSnapshot captureSnapshot() {
        try {
            WorldStatsProvider.Worlds worlds = worldStatsProvider.worlds();
            return new HealthSnapshot(
                worlds.targetTps(),
                HealthCollector.cpu(),
                HealthCollector.memory(),
                HealthCollector.gc(),
                HealthCollector.system(),
                HealthCollector.disk(),
                HealthCollector.threads(),
                worlds.worlds(),
                List.of()
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Health snapshot capture failed.", e);
            return null;
        }
    }

    private List<DiagnosticSection> withModContribution(List<DiagnosticSection> base, Path recording) {
        try {
            java.util.LinkedHashMap<String, String> mods = JfrHotThreads.modContribution(recording, 1000);
            if (mods.isEmpty()) {
                return base;
            }
            List<DiagnosticSection> out = new ArrayList<>(base.size() + 1);
            out.add(new DiagnosticSection("Mod hot-path contribution (JFR)", mods));
            out.addAll(base);
            return out;
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR mod-attribution parse failed.", e);
            return base;
        }
    }

    private HealthSnapshot withHotThreads(HealthSnapshot snapshot, Path recording) {
        if (snapshot == null) {
            return null;
        }
        try {
            return new HealthSnapshot(
                snapshot.targetTps(),
                snapshot.cpu(),
                snapshot.memory(),
                snapshot.gc(),
                snapshot.system(),
                snapshot.disk(),
                snapshot.threads(),
                snapshot.worlds(),
                JfrHotThreads.parse(recording, 1000)
            );
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "JFR hot-thread parse failed.", e);
            return snapshot;
        }
    }

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
        ".txt", ".json", ".html", ".log", ".properties", ".xml", ".yaml", ".yml", ".cfg"
    );

    private List<BundleAttachment> redactExtras(List<BundleAttachment> extras) {
        List<BundleAttachment> result = new ArrayList<>(extras.size());
        for (BundleAttachment extra : extras) {
            if (isTextPath(extra.pathInZip())) {
                result.add(new BundleAttachment(extra.pathInZip(), redactor.redact(extra.data())));
            } else {
                result.add(extra);
            }
        }
        return result;
    }

    private List<DiagnosticSection> redactDiagnostics(List<DiagnosticSection> sections) {
        List<DiagnosticSection> out = new ArrayList<>(sections.size());
        for (DiagnosticSection section : sections) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : section.entries().entrySet()) {
                entries.put(entry.getKey(), redactor.redact(entry.getValue()));
            }
            String pre = section.preformatted() == null ? null : redactor.redact(section.preformatted());
            out.add(new DiagnosticSection(section.title(), entries, pre));
        }
        return out;
    }

    private Map<String, String> redactContext(Map<String, String> context) {
        Map<String, String> redacted = new LinkedHashMap<>(context.size());
        for (Map.Entry<String, String> entry : context.entrySet()) {
            redacted.put(entry.getKey(), redactor.redact(entry.getValue()));
        }
        return redacted;
    }

    private static boolean isTextPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(path.substring(dot).toLowerCase(java.util.Locale.ROOT));
    }
}
