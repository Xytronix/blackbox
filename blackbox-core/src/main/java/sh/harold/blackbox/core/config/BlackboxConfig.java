package sh.harold.blackbox.core.config;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import sh.harold.blackbox.core.capture.CapturePolicy;
import sh.harold.blackbox.core.notify.discord.DiscordWebhookConfig;
import sh.harold.blackbox.core.trigger.TriggerPolicy;

/**
 * Parsed configuration for Blackbox.
 */
public record BlackboxConfig(
    Duration jfrMaxAge,
    long jfrMaxSizeBytes,
    String jfrRecordingName,
    List<String> jfrDisabledEvents,
    TriggerPolicy triggerPolicy,
    CapturePolicy capturePolicy,
    DiscordWebhookConfig discordWebhook,
    Duration postIncidentMaxWait,
    Duration jfrSnapshotInterval,
    String jfrConfiguration,
    Duration jfrSampleInterval,
    boolean jfrOldObjectSampling,
    boolean metricsEnabled,
    int metricsRetentionDays,
    boolean metricsCpu,
    boolean metricsAllocation,
    boolean prometheusEnabled,
    int prometheusPort,
    String prometheusBind,
    int threadDumpCount,
    Duration threadDumpInterval
) {
    private static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofSeconds(10);
    private static final Duration MIN_SAMPLE_INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAX_SAMPLE_INTERVAL = Duration.ofMinutes(5);
    private static final int DEFAULT_METRICS_RETENTION_DAYS = 7;
    private static final int DEFAULT_PROMETHEUS_PORT = 9099;
    private static final String DEFAULT_PROMETHEUS_BIND = "127.0.0.1";
    private static final int DEFAULT_THREAD_DUMP_COUNT = 3;
    private static final Duration DEFAULT_THREAD_DUMP_INTERVAL = Duration.ofSeconds(10);

    public BlackboxConfig {
        Objects.requireNonNull(jfrMaxAge, "jfrMaxAge");
        Objects.requireNonNull(jfrRecordingName, "jfrRecordingName");
        Objects.requireNonNull(jfrDisabledEvents, "jfrDisabledEvents");
        jfrDisabledEvents = List.copyOf(jfrDisabledEvents);
        Objects.requireNonNull(triggerPolicy, "triggerPolicy");
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(discordWebhook, "discordWebhook");
        postIncidentMaxWait = postIncidentMaxWait == null ? Duration.ZERO : postIncidentMaxWait;
        jfrSnapshotInterval = jfrSnapshotInterval == null ? Duration.ZERO : jfrSnapshotInterval;
        if (jfrMaxAge.isNegative()) {
            throw new IllegalArgumentException("jfrMaxAge must be >= 0 (0 = unlimited).");
        }
        if (jfrMaxSizeBytes < 0) {
            throw new IllegalArgumentException("jfrMaxSizeBytes must be >= 0 (0 = unlimited).");
        }
        if (jfrRecordingName.isBlank()) {
            throw new IllegalArgumentException("jfrRecordingName must be non-blank.");
        }
        if (postIncidentMaxWait.isNegative()) {
            throw new IllegalArgumentException("postIncidentMaxWait must be >= 0.");
        }
        if (jfrSnapshotInterval.isNegative()) {
            throw new IllegalArgumentException("jfrSnapshotInterval must be >= 0.");
        }
        jfrConfiguration = jfrConfiguration == null || jfrConfiguration.isBlank()
            ? "default" : jfrConfiguration;
        jfrSampleInterval = jfrSampleInterval == null ? DEFAULT_SAMPLE_INTERVAL : jfrSampleInterval;
        if (jfrSampleInterval.compareTo(MIN_SAMPLE_INTERVAL) < 0) {
            jfrSampleInterval = MIN_SAMPLE_INTERVAL;
        } else if (jfrSampleInterval.compareTo(MAX_SAMPLE_INTERVAL) > 0) {
            jfrSampleInterval = MAX_SAMPLE_INTERVAL;
        }
        if (metricsRetentionDays <= 0) {
            metricsRetentionDays = DEFAULT_METRICS_RETENTION_DAYS;
        }
        if (prometheusPort <= 0 || prometheusPort > 65535) {
            prometheusPort = DEFAULT_PROMETHEUS_PORT;
        }
        prometheusBind = prometheusBind == null || prometheusBind.isBlank()
            ? DEFAULT_PROMETHEUS_BIND : prometheusBind;
        if (threadDumpCount <= 0) {
            threadDumpCount = DEFAULT_THREAD_DUMP_COUNT;
        }
        threadDumpInterval = threadDumpInterval == null
            ? DEFAULT_THREAD_DUMP_INTERVAL : threadDumpInterval;
        if (threadDumpInterval.isNegative()) {
            throw new IllegalArgumentException("threadDumpInterval must be >= 0.");
        }
    }

    public BlackboxConfig(
        Duration jfrMaxAge,
        long jfrMaxSizeBytes,
        String jfrRecordingName,
        List<String> jfrDisabledEvents,
        TriggerPolicy triggerPolicy,
        CapturePolicy capturePolicy,
        DiscordWebhookConfig discordWebhook,
        Duration postIncidentMaxWait,
        Duration jfrSnapshotInterval,
        String jfrConfiguration,
        Duration jfrSampleInterval
    ) {
        this(jfrMaxAge, jfrMaxSizeBytes, jfrRecordingName, jfrDisabledEvents, triggerPolicy,
             capturePolicy, discordWebhook, postIncidentMaxWait, jfrSnapshotInterval,
             jfrConfiguration, jfrSampleInterval, false, false, DEFAULT_METRICS_RETENTION_DAYS,
             true, false, false, DEFAULT_PROMETHEUS_PORT, DEFAULT_PROMETHEUS_BIND,
             DEFAULT_THREAD_DUMP_COUNT, DEFAULT_THREAD_DUMP_INTERVAL);
    }

    public BlackboxConfig(
        Duration jfrMaxAge,
        long jfrMaxSizeBytes,
        String jfrRecordingName,
        List<String> jfrDisabledEvents,
        TriggerPolicy triggerPolicy,
        CapturePolicy capturePolicy,
        DiscordWebhookConfig discordWebhook,
        Duration postIncidentMaxWait,
        Duration jfrSnapshotInterval,
        String jfrConfiguration
    ) {
        this(jfrMaxAge, jfrMaxSizeBytes, jfrRecordingName, jfrDisabledEvents, triggerPolicy,
             capturePolicy, discordWebhook, postIncidentMaxWait, jfrSnapshotInterval,
             jfrConfiguration, null);
    }

    public BlackboxConfig(
        Duration jfrMaxAge,
        long jfrMaxSizeBytes,
        String jfrRecordingName,
        TriggerPolicy triggerPolicy,
        CapturePolicy capturePolicy,
        DiscordWebhookConfig discordWebhook
    ) {
        this(jfrMaxAge, jfrMaxSizeBytes, jfrRecordingName, List.of(),
             triggerPolicy, capturePolicy, discordWebhook, Duration.ZERO, Duration.ZERO,
             "default", null);
    }
}
