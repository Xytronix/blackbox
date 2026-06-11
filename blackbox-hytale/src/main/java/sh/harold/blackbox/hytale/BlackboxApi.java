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

    public static void recordEvent(String category, String message) {
        try {
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = clip(category, 48);
            event.message = clip(message, 200);
            event.commit();
        } catch (Throwable ignored) {
        }
    }

    public static void recordCount(String name, long delta) {
        commitMetric(name, delta, true);
    }

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
