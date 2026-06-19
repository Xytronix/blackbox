package sh.harold.blackbox.core.capture;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import sh.harold.blackbox.core.bundle.BundleArtifacts;
import sh.harold.blackbox.core.retention.RetentionPolicy;

/**
 * Capture policy container.
 */
public record CapturePolicy(
    RetentionPolicy retention,
    boolean enabled,
    boolean allowPluginExtras,
    int logTailLines,
    List<String> redactPatterns,
    Set<String> artifacts,
    boolean heapHistogram,
    boolean includeServerLog,
    boolean includeServerConfig,
    boolean includeModConfigs,
    boolean allowModConfigOptIn,
    boolean sanitizeLog,
    List<String> frameworkPrefixes
) {
    public CapturePolicy {
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(redactPatterns, "redactPatterns");
        redactPatterns = List.copyOf(redactPatterns);
        artifacts = artifacts == null ? BundleArtifacts.ALL : Set.copyOf(artifacts);
        frameworkPrefixes = frameworkPrefixes == null ? List.of() : List.copyOf(frameworkPrefixes);
        if (logTailLines < 0) {
            throw new IllegalArgumentException("logTailLines must be >= 0.");
        }
    }

    public CapturePolicy(RetentionPolicy retention, boolean enabled, boolean allowPluginExtras,
                         int logTailLines, List<String> redactPatterns, Set<String> artifacts,
                         boolean heapHistogram) {
        this(retention, enabled, allowPluginExtras, logTailLines, redactPatterns, artifacts, heapHistogram,
            false, false, false, true, true, List.of());
    }

    public CapturePolicy(RetentionPolicy retention, boolean enabled, boolean allowPluginExtras,
                         int logTailLines, List<String> redactPatterns, Set<String> artifacts) {
        this(retention, enabled, allowPluginExtras, logTailLines, redactPatterns, artifacts, false);
    }

    public CapturePolicy(RetentionPolicy retention, boolean enabled, boolean allowPluginExtras,
                         int logTailLines, List<String> redactPatterns) {
        this(retention, enabled, allowPluginExtras, logTailLines, redactPatterns, BundleArtifacts.ALL, false);
    }

    public CapturePolicy(RetentionPolicy retention, boolean enabled, boolean allowPluginExtras, int logTailLines) {
        this(retention, enabled, allowPluginExtras, logTailLines, List.of(), BundleArtifacts.ALL);
    }

    public CapturePolicy(RetentionPolicy retention) {
        this(retention, true, true, 0, List.of(), BundleArtifacts.ALL);
    }

    public CapturePolicy withFrameworkPrefixes(List<String> prefixes) {
        return new CapturePolicy(retention, enabled, allowPluginExtras, logTailLines, redactPatterns, artifacts,
            heapHistogram, includeServerLog, includeServerConfig, includeModConfigs, allowModConfigOptIn,
            sanitizeLog, prefixes);
    }
}

