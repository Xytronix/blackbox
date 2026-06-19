package sh.harold.blackbox.core.retention;

import java.time.Duration;

/**
 * Defines retention limits for stored incident bundles.
 */
public record RetentionPolicy(
    int maxCount,
    long maxTotalBytes,
    Duration maxAge,
    int failedRecordingMaxCount
) {
    public static final int DEFAULT_FAILED_RECORDING_MAX_COUNT = 5;

    public RetentionPolicy {
        if (maxCount < 0) {
            throw new IllegalArgumentException("maxCount must be >= 0.");
        }
        if (maxTotalBytes < 0) {
            throw new IllegalArgumentException("maxTotalBytes must be >= 0.");
        }
        if (maxAge != null && maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be non-negative.");
        }
        if (failedRecordingMaxCount < 0) {
            throw new IllegalArgumentException("failedRecordingMaxCount must be >= 0.");
        }
    }

    public RetentionPolicy(int maxCount, long maxTotalBytes, Duration maxAge) {
        this(maxCount, maxTotalBytes, maxAge, DEFAULT_FAILED_RECORDING_MAX_COUNT);
    }
}
