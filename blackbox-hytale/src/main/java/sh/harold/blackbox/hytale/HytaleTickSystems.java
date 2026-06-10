package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Builds a "Tick systems" diagnostics section from the engine's own per-system tick metrics:
 * {@code Store.tickInternal} wraps every ticking ECS system in nanoTime and records the exact
 * wall-clock cost into {@code Store.getSystemMetrics()} (rolling windows 1s/1m/5m). Unlike CPU
 * sampling this includes blocking time, so a system stuck on a lock or IO shows up here.
 * Entries are "&lt;system&gt; @ &lt;world&gt;" with "&lt;avg1m&gt; &lt;max1m&gt; &lt;avg5m&gt;
 * &lt;max5m&gt;" millisecond values. When the metrics exist but read all-zero, a single "Note"
 * entry says they are disabled (e.g. by a mixin that skips system metric recording).
 */
final class HytaleTickSystems {
    private static final System.Logger LOGGER = System.getLogger(HytaleTickSystems.class.getName());
    private static final int PER_WORLD_LIMIT = 20;
    private static final int PERIOD_1S = 0;
    private static final int PERIOD_1M = 1;
    private static final int PERIOD_5M = 2;
    private static final String DISABLED_NOTE = "system metrics disabled";

    private HytaleTickSystems() {
    }

    /** A per-system tick-cost sample for the JFR sampler: the 1s-window average in millis. */
    record SystemSample(String world, String system, double avgMs) {}

    /** Returns {@code base} with a "Tick systems" section appended when any metrics exist. */
    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        try {
            Map<String, String> entries = entries();
            if (entries.isEmpty()) {
                return base;
            }
            List<DiagnosticSection> out = new ArrayList<>(base);
            out.add(new DiagnosticSection("Tick systems", entries));
            return out;
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Tick system metrics collection failed.", t);
            return base;
        }
    }

    private record Row(String system, String world, double avg1m, double max1m, double avg5m, double max5m) {}

    private static Map<String, String> entries() {
        List<Row> rows = new ArrayList<>();
        boolean sawMetric = false;
        for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
            String worldName = entry.getKey();
            World world = entry.getValue();
            if (worldName == null || worldName.isBlank() || world == null) {
                continue;
            }
            List<Row> worldRows = new ArrayList<>();
            try {
                var store = world.getEntityStore().getStore();
                var data = store.getRegistry().getData();
                HistoricMetric[] metrics = store.getSystemMetrics();
                int size = Math.min(data.getSystemSize(), metrics.length);
                for (int i = 0; i < size; i++) {
                    HistoricMetric metric = metrics[i];
                    if (metric == null) {
                        continue;
                    }
                    sawMetric = true;
                    try {
                        double avg1m = nanosToMs(metric.getAverage(PERIOD_1M));
                        double max1m = nanosToMs(metric.calculateMax(PERIOD_1M));
                        double avg5m = nanosToMs(metric.getAverage(PERIOD_5M));
                        double max5m = nanosToMs(metric.calculateMax(PERIOD_5M));
                        if (avg1m <= 0 && max1m <= 0 && avg5m <= 0) {
                            continue;
                        }
                        worldRows.add(new Row(systemName(data.getSystem(i)), worldName,
                            avg1m, max1m, avg5m, max5m));
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            worldRows.sort((a, b) -> Double.compare(b.avg1m(), a.avg1m()));
            rows.addAll(worldRows.subList(0, Math.min(PER_WORLD_LIMIT, worldRows.size())));
        }

        if (rows.isEmpty()) {
            if (sawMetric) {
                Map<String, String> note = new LinkedHashMap<>();
                note.put("Note", DISABLED_NOTE);
                return note;
            }
            return Map.of();
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.system() + " @ " + row.world();
            String unique = key;
            int suffix = 2;
            while (out.containsKey(unique)) {
                unique = key + " #" + suffix++;
            }
            out.put(unique, String.format(Locale.ROOT, "%.2f %.2f %.2f %.2f",
                row.avg1m(), row.max1m(), row.avg5m(), row.max5m()));
        }
        return out;
    }

    /** Top {@code perWorldLimit} systems per world by 1s-window average tick cost. */
    static List<SystemSample> topSystems(int perWorldLimit) {
        List<SystemSample> samples = new ArrayList<>();
        try {
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String worldName = entry.getKey();
                World world = entry.getValue();
                if (worldName == null || worldName.isBlank() || world == null) {
                    continue;
                }
                try {
                    var store = world.getEntityStore().getStore();
                    var data = store.getRegistry().getData();
                    HistoricMetric[] metrics = store.getSystemMetrics();
                    int size = Math.min(data.getSystemSize(), metrics.length);
                    List<SystemSample> worldSamples = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        HistoricMetric metric = metrics[i];
                        if (metric == null) {
                            continue;
                        }
                        double avgMs = nanosToMs(metric.getAverage(PERIOD_1S));
                        if (avgMs > 0) {
                            worldSamples.add(new SystemSample(worldName, systemName(data.getSystem(i)), avgMs));
                        }
                    }
                    worldSamples.sort((a, b) -> Double.compare(b.avgMs(), a.avgMs()));
                    samples.addAll(worldSamples.subList(0, Math.min(perWorldLimit, worldSamples.size())));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        return samples;
    }

    private static String systemName(Object system) {
        if (system == null) {
            return "<unknown>";
        }
        String simple = system.getClass().getSimpleName();
        if (!simple.isBlank()) {
            return simple;
        }
        String name = system.getClass().getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static double nanosToMs(double nanos) {
        return Double.isFinite(nanos) && nanos > 0 ? nanos / 1_000_000.0 : 0;
    }
}
