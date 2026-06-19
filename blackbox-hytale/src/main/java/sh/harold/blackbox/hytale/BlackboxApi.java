package sh.harold.blackbox.hytale;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import sh.harold.blackbox.core.capture.BundleExtrasProvider;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.jfr.BlackboxPluginEvent;
import sh.harold.blackbox.core.jfr.BlackboxPluginMetricEvent;

/**
 * Public API for third-party plugins to integrate with Blackbox.
 * Example:
 * BlackboxApi.registerExtras((report, event) -> List.of(
 *     new BundleAttachment("extras/my-plugin.txt", myData.getBytes())
 * ));
 */
public final class BlackboxApi {
    private static final int MAX_REGISTRATIONS = 64;

    private static volatile BlackboxRuntime runtime;
    private static final List<DiagnosticRegistration> DIAGNOSTICS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Path> CONFIG_PATHS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, Double> PLUGIN_GAUGES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> PLUGIN_COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DoubleSupplier> PLUGIN_GAUGE_SUPPLIERS = new ConcurrentHashMap<>();

    private BlackboxApi() {
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        void unregister();

        @Override
        default void close() {
            unregister();
        }
    }

    static void init(BlackboxRuntime runtime) {
        BlackboxApi.runtime = runtime;
    }

    static void shutdown() {
        BlackboxApi.runtime = null;
        DIAGNOSTICS.clear();
        CONFIG_PATHS.clear();
        PLUGIN_GAUGE_SUPPLIERS.clear();
    }

    public static Registration registerExtras(BundleExtrasProvider provider) {
        Objects.requireNonNull(provider, "provider");
        BlackboxRuntime rt = runtime;
        if (rt == null) {
            throw new IllegalStateException("Blackbox is not initialized.");
        }
        if (!rt.config().capturePolicy().allowPluginExtras()) {
            throw new IllegalStateException("Plugin extras are disabled (Capture.AllowPluginExtras=false).");
        }
        if (rt.extrasRegistry().size() >= MAX_REGISTRATIONS) {
            throw new IllegalStateException("Extras registration limit reached (" + MAX_REGISTRATIONS + ").");
        }
        rt.extrasRegistry().register(provider);
        return () -> rt.extrasRegistry().unregister(provider);
    }

    public static Registration registerConfig(Path file) {
        Objects.requireNonNull(file, "file");
        BlackboxRuntime rt = runtime;
        if (rt == null) {
            throw new IllegalStateException("Blackbox is not initialized.");
        }
        if (!rt.config().capturePolicy().allowModConfigOptIn()) {
            throw new IllegalStateException("Mod config opt-in is disabled (Capture.AllowModConfigOptIn=false).");
        }
        if (CONFIG_PATHS.size() >= MAX_REGISTRATIONS) {
            throw new IllegalStateException("Config registration limit reached (" + MAX_REGISTRATIONS + ").");
        }
        CONFIG_PATHS.addIfAbsent(file);
        return () -> CONFIG_PATHS.remove(file);
    }

    static List<Path> registeredConfigPaths() {
        return List.copyOf(CONFIG_PATHS);
    }

    public static void recordEvent(String category, String message) {
        try {
            BlackboxRuntime rt = runtime;
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = clip(rt == null ? category : rt.maskText(category), 48);
            event.message = clip(rt == null ? message : rt.maskText(message), 200);
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

    public static Registration registerGauge(String name, DoubleSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        String key = clip(name, 64);
        if (PLUGIN_GAUGE_SUPPLIERS.size() >= MAX_REGISTRATIONS && !PLUGIN_GAUGE_SUPPLIERS.containsKey(key)) {
            throw new IllegalStateException("Gauge registration limit reached (" + MAX_REGISTRATIONS + ").");
        }
        PLUGIN_GAUGE_SUPPLIERS.put(key, supplier);
        return () -> PLUGIN_GAUGE_SUPPLIERS.remove(key, supplier);
    }

    private static void commitMetric(String name, double value, boolean counter) {
        try {
            if (name == null || name.isBlank() || !Double.isFinite(value)) {
                return;
            }
            String key = clip(name, 64);
            BlackboxPluginMetricEvent event = new BlackboxPluginMetricEvent();
            event.name = key;
            event.value = value;
            event.counter = counter;
            event.commit();

            ConcurrentHashMap<String, Double> store = counter ? PLUGIN_COUNTERS : PLUGIN_GAUGES;
            if (store.size() >= MAX_REGISTRATIONS && !store.containsKey(key)) {
                return;
            }
            if (counter) {
                store.merge(key, value, Double::sum);
            } else {
                store.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    static Map<String, Double> pluginGauges() {
        return Map.copyOf(PLUGIN_GAUGES);
    }

    static Map<String, Double> sampleSupplierGauges() {
        Map<String, Double> sampled = new HashMap<>();
        for (Map.Entry<String, DoubleSupplier> entry : PLUGIN_GAUGE_SUPPLIERS.entrySet()) {
            double value;
            try {
                value = entry.getValue().getAsDouble();
            } catch (Throwable t) {
                continue;
            }
            if (!Double.isFinite(value)) {
                continue;
            }
            sampled.put(entry.getKey(), value);
            BlackboxPluginMetricEvent event = new BlackboxPluginMetricEvent();
            event.name = entry.getKey();
            event.value = value;
            event.counter = false;
            event.commit();
        }
        return Map.copyOf(sampled);
    }

    static Map<String, Double> pluginCounters() {
        return Map.copyOf(PLUGIN_COUNTERS);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public static Registration registerDiagnostics(String title, Supplier<Map<String, String>> supplier) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(supplier, "supplier");
        if (DIAGNOSTICS.size() >= MAX_REGISTRATIONS) {
            throw new IllegalStateException("Diagnostic registration limit reached (" + MAX_REGISTRATIONS + ").");
        }
        DiagnosticRegistration registration = new DiagnosticRegistration(title, supplier);
        DIAGNOSTICS.add(registration);
        return () -> DIAGNOSTICS.remove(registration);
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
