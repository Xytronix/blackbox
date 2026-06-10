package sh.harold.blackbox.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import jdk.jfr.Configuration;
import jdk.jfr.EventSettings;
import jdk.jfr.Recording;

/**
 * Controls a single rolling JFR recording and exposes a minimal API for dumping it.
 */
public final class JfrController implements AutoCloseable {
    private static final String DEFAULT_CONFIGURATION = "default";
    private static final String DUMP_MARKER_PREFIX = "blackbox dump:";

    private final Duration maxAge;
    private final long maxSizeBytes;
    private final String recordingName;
    private final List<String> disabledEvents;
    private final String configurationName;
    private Recording recording;
    private final System.Logger logger = System.getLogger(JfrController.class.getName());

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName) {
        this(maxAge, maxSizeBytes, recordingName, List.of());
    }

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName, List<String> disabledEvents) {
        this(maxAge, maxSizeBytes, recordingName, disabledEvents, DEFAULT_CONFIGURATION);
    }

    /**
     * @param configurationName JFR settings profile: "default" (about 1% overhead) or "profile"
     *                          (higher-fidelity sampling, about 2% overhead, the profiler mode)
     */
    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName,
                         List<String> disabledEvents, String configurationName) {
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.maxSizeBytes = maxSizeBytes;
        this.recordingName = Objects.requireNonNull(recordingName, "recordingName");
        this.disabledEvents = List.copyOf(Objects.requireNonNull(disabledEvents, "disabledEvents"));
        this.configurationName = configurationName == null || configurationName.isBlank()
            ? DEFAULT_CONFIGURATION : configurationName;
    }

    public void start() {
        if (recording != null) {
            return;
        }
        startWith(configurationName);
    }

    /**
     * Discards the current recording (the rolling buffer is lost) and starts a fresh one with
     * the given settings profile; null or blank reverts to the configured profile. Used by the
     * on-demand profiler mode. JFR treats {@code setMaxAge(null)} and {@code setMaxSize(0)} as
     * no cap; with neither cap set the on-disk recording can grow without bound.
     */
    public synchronized void restart(String configurationOverride) {
        close();
        startWith(configurationOverride == null || configurationOverride.isBlank()
            ? configurationName : configurationOverride);
    }

    private void startWith(String configuration) {
        Recording created = createConfiguredRecording(configuration);
        created.setName(recordingName);
        created.setToDisk(true);
        created.setMaxAge(maxAge.isZero() ? null : maxAge);
        created.setMaxSize(Math.max(0L, maxSizeBytes));
        if (maxAge.isZero() && maxSizeBytes <= 0L) {
            logger.log(System.Logger.Level.WARNING,
                "JFR recording has no age or size cap (both unlimited); the on-disk recording can grow until it "
                + "fills the disk. Set Jfr.MaxAge or Jfr.MaxSizeBytes to a positive value to bound it.");
        }
        enableMarkerEvent(created);
        enableOldObjectSampling(created);
        disableConfiguredEvents(created);
        created.start();
        this.recording = created;
    }

    public EventSettings enableEvent(String eventName) {
        return requireRecording().enable(eventName);
    }

    public void dump(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BlackboxMarkerEvent marker = new BlackboxMarkerEvent();
        marker.message = DUMP_MARKER_PREFIX + " " + target.getFileName();
        marker.commit();
        requireRecording().dump(target);
    }

    @Override
    public void close() {
        if (recording == null) {
            return;
        }
        recording.stop();
        recording.close();
        recording = null;
    }

    private Recording requireRecording() {
        if (recording == null) {
            throw new IllegalStateException("Recording has not been started.");
        }
        return recording;
    }

    private Recording createConfiguredRecording(String configurationName) {
        Recording configured = tryLoadConfiguration(configurationName);
        if (configured != null) {
            return configured;
        }

        if (!"profile".equals(configurationName)) {
            Recording profile = tryLoadConfiguration("profile");
            if (profile != null) {
                return profile;
            }
        }

        logger.log(System.Logger.Level.WARNING, "Failed to load JFR configurations. Falling back to an unconfigured recording.");
        return new Recording();
    }

    private void enableMarkerEvent(Recording recording) {
        try {
            recording.enable(BlackboxMarkerEvent.class)
                .withoutStackTrace()
                .withThreshold(Duration.ZERO);
        } catch (IllegalArgumentException e) {
            logger.log(System.Logger.Level.WARNING, "Failed to enable marker event.", e);
        }
    }

    /**
     * Enables long-lived-object sampling so the report can list leak candidates. The "default"
     * configuration leaves jdk.OldObjectSample off; cutoff "0 ns" keeps every sample eligible
     * (the JMC leak-profiling recipe) while the sampler's fixed internal queue bounds the cost.
     * Disable via Jfr.DisabledEvents=["jdk.OldObjectSample"] if unwanted.
     */
    private void enableOldObjectSampling(Recording recording) {
        try {
            recording.enable("jdk.OldObjectSample")
                .withStackTrace()
                .with("cutoff", "0 ns");
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to enable old-object sampling.", e);
        }
    }

    private void disableConfiguredEvents(Recording recording) {
        for (String eventName : disabledEvents) {
            try {
                recording.disable(eventName);
                logger.log(System.Logger.Level.DEBUG, "Disabled JFR event: " + eventName);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to disable JFR event '" + eventName + "'.", e);
            }
        }
    }

    private Recording tryLoadConfiguration(String configurationName) {
        try {
            return new Recording(Configuration.getConfiguration(configurationName));
        } catch (IOException | ParseException e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to load JFR configuration '" + configurationName + "'.", e);
            return null;
        }
    }
}
