package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import sh.harold.blackbox.core.capture.BundleExtrasProvider;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.jfr.BlackboxPluginEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginMetricEvent;

/**
 * Public API for third-party plugins to integrate with Blackbox.
 *
 * <p>Add files to every bundle:
 * <pre>BlackboxApi.registerExtras((report, event) -> List.of(
 *     new BundleAttachment("extras/my-plugin.txt", myData.getBytes())));</pre>
 *
 * <p>Add a config/diagnostics section to every report.html (sampled at capture time):
 * <pre>BlackboxApi.registerDiagnostics("My Plugin", () -> Map.of("version", "1.0", "mode", "fast"));</pre>
 *
 * <p>Mark a notable moment on the report's chart timelines (recorded into the rolling JFR
 * buffer, so it shows on every incident whose window covers it):
 * <pre>BlackboxApi.recordEvent("AiTickThrottler", "throttled 42 entities in world default");</pre>
 *
 * <p>Chart numeric metrics on the report's timeline. A count is work done since the last call
 * (summed per interval); a gauge is a current value (stepped line):
 * <pre>BlackboxApi.recordCount("ChunkUnloader unloaded", 180);
 * BlackboxApi.recordGauge("AiTickThrottler frozen", 42);</pre>
 */
public final class BlackboxApi {
    private static volatile BlackboxRuntime runtime;
    private static final List<DiagnosticRegistration> DIAGNOSTICS = new CopyOnWriteArrayList<>();

    private BlackboxApi() {
    }

    static void init(BlackboxRuntime runtime) {
        BlackboxApi.runtime = runtime;
    }

    static void shutdown() {
        BlackboxApi.runtime = null;
        DIAGNOSTICS.clear();
    }

    public static void registerExtras(BundleExtrasProvider provider) {
        BlackboxRuntime rt = runtime;
        if (rt == null) {
            throw new IllegalStateException("Blackbox is not initialized.");
        }
        if (!rt.config().capturePolicy().allowPluginExtras()) {
            throw new IllegalStateException("Plugin extras are disabled (Capture.AllowPluginExtras=false).");
        }
        rt.extrasRegistry().register(provider);
    }

    /**
     * Records a plugin event into the rolling recording; it appears on the report's chart
     * timelines when an incident window covers it. Never throws and needs no initialization;
     * category is capped at 48 chars, message at 200.
     */
    public static void recordEvent(String category, String message) {
        try {
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = clip(category, 48);
            event.message = clip(message, 200);
            event.commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Records a counter delta (work done since the previous call), charted in the report as a
     * per-interval sum. Never throws and needs no initialization; the name is capped at 64 chars.
     */
    public static void recordCount(String name, long delta) {
        commitMetric(name, delta, true);
    }

    /**
     * Records the current value of a gauge, charted in the report as a stepped line. Never
     * throws and needs no initialization; the name is capped at 64 chars.
     */
    public static void recordGauge(String name, double value) {
        commitMetric(name, value, false);
    }

    private static void commitMetric(String name, double value, boolean counter) {
        try {
            if (name == null || name.isBlank() || !Double.isFinite(value)) {
                return;
            }
            BlackboxPluginMetricEvent event = new BlackboxPluginMetricEvent();
            event.name = clip(name, 64);
            event.value = value;
            event.counter = counter;
            event.commit();
        } catch (Throwable ignored) {
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /**
     * Registers a named key/value section rendered in report.html. The supplier is invoked
     * at capture time, so it always reflects current state (e.g. live config).
     */
    public static void registerDiagnostics(String title, Supplier<Map<String, String>> supplier) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(supplier, "supplier");
        DIAGNOSTICS.add(new DiagnosticRegistration(title, supplier));
    }

    static List<DiagnosticSection> collectDiagnostics() {
        List<DiagnosticSection> out = new ArrayList<>();
        for (DiagnosticRegistration reg : DIAGNOSTICS) {
            try {
                out.add(new DiagnosticSection(reg.title(), reg.supplier().get()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private record DiagnosticRegistration(String title, Supplier<Map<String, String>> supplier) {
    }
}
