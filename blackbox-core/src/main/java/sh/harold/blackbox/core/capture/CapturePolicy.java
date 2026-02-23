package sh.harold.blackbox.core.capture;

import java.util.List;
import java.util.Objects;

import sh.harold.blackbox.core.retention.RetentionPolicy;

/**
 * Capture policy container.
 */
public record CapturePolicy(
    RetentionPolicy retention,
    boolean enabled,
    boolean allowPluginExtras,
    int logTailLines,
    List<String> redactPatterns
) {
    public CapturePolicy {
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(redactPatterns, "redactPatterns");
        redactPatterns = List.copyOf(redactPatterns);
        if (logTailLines < 0) {
            throw new IllegalArgumentException("logTailLines must be >= 0.");
        }
    }

    public CapturePolicy(RetentionPolicy retention, boolean enabled, boolean allowPluginExtras, int logTailLines) {
        this(retention, enabled, allowPluginExtras, logTailLines, List.of());
    }

    public CapturePolicy(RetentionPolicy retention) {
        this(retention, true, true, 0, List.of());
    }
}

