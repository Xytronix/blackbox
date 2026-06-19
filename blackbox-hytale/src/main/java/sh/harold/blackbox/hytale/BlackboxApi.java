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
 * Every entry point takes a {@code plugin} owner token used for namespacing,
 * per-plugin quotas, and {@link #clearPlugin(String)} cleanup.
 * Example:
 * BlackboxApi.registerExtras("my-plugin", (report, event) -> List.of(
 *     new BundleAttachment("extras/my-plugin.txt", myData.getBytes())
 * ));
 */
public final class BlackboxApi {
    private static final int MAX_PER_PLUGIN = 64;
    private static final Registration NO_OP = () -> {
    };

    private static volatile BlackboxRuntime runtime;
    private static final List<DiagnosticRegistration> DIAGNOSTICS = new CopyOnWriteArrayList<>();
    private static final List<OwnedConfig> CONFIG_PATHS = new CopyOnWriteArrayList<>();
    private static final List<OwnedExtras> EXTRAS = new CopyOnWriteArrayList<>();
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
        EXTRAS.clear();
        PLUGIN_GAUGES.clear();
        PLUGIN_COUNTERS.clear();
        PLUGIN_GAUGE_SUPPLIERS.clear();
    }

    public static void clearPlugin(String plugin) {
        String owner = owner(plugin);
        DIAGNOSTICS.removeIf(r -> r.owner().equals(owner));
        CONFIG_PATHS.removeIf(c -> c.owner().equals(owner));
        BlackboxRuntime rt = runtime;
        EXTRAS.removeIf(e -> {
            if (!e.owner().equals(owner)) {
                return false;
            }
            if (rt != null) {
                rt.extrasRegistry().unregister(e.provider());
            }
            return true;
        });
        String prefix = owner + "/";
        PLUGIN_GAUGES.keySet().removeIf(k -> k.startsWith(prefix));
        PLUGIN_COUNTERS.keySet().removeIf(k -> k.startsWith(prefix));
        PLUGIN_GAUGE_SUPPLIERS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static Registration registerExtras(String plugin, BundleExtrasProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String owner = owner(plugin);
        BlackboxRuntime rt = runtime;
        if (rt == null || !rt.config().capturePolicy().allowPluginExtras()) {
            return NO_OP;
        }
        if (ownerCount(EXTRAS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Extras registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        OwnedExtras entry = new OwnedExtras(owner, provider);
        EXTRAS.add(entry);
        rt.extrasRegistry().register(provider);
        return () -> {
            if (EXTRAS.remove(entry)) {
                rt.extrasRegistry().unregister(provider);
            }
        };
    }

    public static Registration registerConfig(String plugin, Path file) {
        Objects.requireNonNull(file, "file");
        String owner = owner(plugin);
        BlackboxRuntime rt = runtime;
        if (rt == null || !rt.config().capturePolicy().allowModConfigOptIn()) {
            return NO_OP;
        }
        if (CONFIG_PATHS.stream().anyMatch(c -> c.file().equals(file))) {
            return () -> CONFIG_PATHS.removeIf(c -> c.file().equals(file));
        }
        if (ownerCount(CONFIG_PATHS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Config registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        OwnedConfig entry = new OwnedConfig(owner, file);
        CONFIG_PATHS.add(entry);
        return () -> CONFIG_PATHS.remove(entry);
    }

    static List<Path> registeredConfigPaths() {
        return CONFIG_PATHS.stream().map(OwnedConfig::file).distinct().toList();
    }

    public static void recordEvent(String plugin, String category, String message) {
        try {
            BlackboxRuntime rt = runtime;
            String maskedCategory = rt == null ? category : rt.maskText(category);
            String maskedMessage = rt == null ? message : rt.maskText(message);
            BlackboxPluginEvent event = new BlackboxPluginEvent();
            event.category = clip(owner(plugin) + "/" + clip(maskedCategory, 40), 64);
            event.message = clip(maskedMessage, 200);
            event.commit();
        } catch (Throwable ignored) {
        }
    }

    public static void recordCount(String plugin, String name, long delta) {
        commitMetric(owner(plugin), name, delta, true);
    }

    public static void recordGauge(String plugin, String name, double value) {
        commitMetric(owner(plugin), name, value, false);
    }

    public static Registration registerGauge(String plugin, String name, DoubleSupplier supplier) {
        Objects.requireNonNull(supplier, "supplier");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank.");
        }
        String owner = owner(plugin);
        String key = key(owner, name);
        if (!PLUGIN_GAUGE_SUPPLIERS.containsKey(key) && ownerEntries(PLUGIN_GAUGE_SUPPLIERS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Gauge registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        PLUGIN_GAUGE_SUPPLIERS.put(key, supplier);
        return () -> PLUGIN_GAUGE_SUPPLIERS.remove(key, supplier);
    }

    private static void commitMetric(String owner, String name, double value, boolean counter) {
        try {
            if (name == null || name.isBlank() || !Double.isFinite(value)) {
                return;
            }
            String key = key(owner, name);
            ConcurrentHashMap<String, Double> store = counter ? PLUGIN_COUNTERS : PLUGIN_GAUGES;
            if (!store.containsKey(key) && ownerEntries(store, owner) >= MAX_PER_PLUGIN) {
                return;
            }
            BlackboxPluginMetricEvent event = new BlackboxPluginMetricEvent();
            event.name = key;
            event.value = value;
            event.counter = counter;
            event.commit();
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

    public static Registration registerDiagnostics(String plugin, String title, Supplier<Map<String, String>> supplier) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(supplier, "supplier");
        String owner = owner(plugin);
        if (ownerCount(DIAGNOSTICS, owner) >= MAX_PER_PLUGIN) {
            throw new IllegalStateException("Diagnostic registration limit reached for '" + owner + "' (" + MAX_PER_PLUGIN + ").");
        }
        DiagnosticRegistration registration = new DiagnosticRegistration(owner, title, supplier);
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

    private static String owner(String plugin) {
        String trimmed = plugin == null ? "" : plugin.trim().replace('/', '_');
        return trimmed.isEmpty() ? "unknown" : clip(trimmed, 48);
    }

    private static String key(String owner, String name) {
        return owner + "/" + clip(name, 64);
    }

    private static long ownerEntries(Map<String, ?> store, String owner) {
        String prefix = owner + "/";
        return store.keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }

    private static long ownerCount(List<? extends Owned> entries, String owner) {
        return entries.stream().filter(e -> e.owner().equals(owner)).count();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private interface Owned {
        String owner();
    }

    private record DiagnosticRegistration(String owner, String title, Supplier<Map<String, String>> supplier)
        implements Owned {
    }

    private record OwnedConfig(String owner, Path file) implements Owned {
    }

    private record OwnedExtras(String owner, BundleExtrasProvider provider) implements Owned {
    }
}
