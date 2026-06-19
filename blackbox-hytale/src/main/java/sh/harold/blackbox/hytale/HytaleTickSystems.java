package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytaleTickSystems {
    private static final System.Logger LOGGER = System.getLogger(HytaleTickSystems.class.getName());
    private static final int PER_WORLD_LIMIT = 20;
    private static final int PERIOD_1S = 0;
    private static final int PERIOD_1M = 1;
    private static final int PERIOD_5M = 2;
    private static final String DISABLED_NOTE = "system metrics disabled";
    private static final long STORE_THREAD_TIMEOUT_MS = 250;

    private HytaleTickSystems() {
    }

    record SystemSample(String world, String system, double avgMs) {}

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

    private record WorldRows(List<Row> rows, boolean sawMetric) {}

    private static Map<String, String> entries() {
        List<Row> rows = new ArrayList<>();
        boolean sawMetric = false;
        for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
            String worldName = entry.getKey();
            World world = entry.getValue();
            if (worldName == null || worldName.isBlank() || world == null) {
                continue;
            }
            WorldRows worldResult = onStoreThread(world, () -> {
                List<Row> collected = new ArrayList<>();
                boolean seen = false;
                var store = world.getEntityStore().getStore();
                var data = store.getRegistry().getData();
                HistoricMetric[] metrics = store.getSystemMetrics();
                int size = Math.min(data.getSystemSize(), metrics.length);
                for (int i = 0; i < size; i++) {
                    HistoricMetric metric = metrics[i];
                    if (metric == null) {
                        continue;
                    }
                    seen = true;
                    try {
                        double avg1m = nanosToMs(metric.getAverage(PERIOD_1M));
                        double max1m = nanosToMs(metric.calculateMax(PERIOD_1M));
                        double avg5m = nanosToMs(metric.getAverage(PERIOD_5M));
                        double max5m = nanosToMs(metric.calculateMax(PERIOD_5M));
                        if (avg1m <= 0 && max1m <= 0 && avg5m <= 0) {
                            continue;
                        }
                        collected.add(new Row(systemName(data.getSystem(i)), worldName,
                            avg1m, max1m, avg5m, max5m));
                    } catch (Throwable ignored) {
                    }
                }
                return new WorldRows(collected, seen);
            });
            if (worldResult == null) {
                continue;
            }
            if (worldResult.sawMetric()) {
                sawMetric = true;
            }
            List<Row> worldRows = worldResult.rows();
            worldRows.sort((a, b) -> Double.compare(b.avg1m(), a.avg1m()));
            rows.addAll(worldRows.subList(0, Math.min(PER_WORLD_LIMIT, worldRows.size())));
        }

        if (rows.isEmpty()) {
            Map<String, String> note = new LinkedHashMap<>();
            note.put("Note", sawMetric ? DISABLED_NOTE
                : "per-system tick metrics not exposed by this server (engine system metrics disabled)");
            return note;
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

    static List<SystemSample> topSystems(int perWorldLimit) {
        List<SystemSample> samples = new ArrayList<>();
        try {
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String worldName = entry.getKey();
                World world = entry.getValue();
                if (worldName == null || worldName.isBlank() || world == null) {
                    continue;
                }
                List<SystemSample> worldSamples = onStoreThread(world, () -> {
                    var store = world.getEntityStore().getStore();
                    var data = store.getRegistry().getData();
                    HistoricMetric[] metrics = store.getSystemMetrics();
                    int size = Math.min(data.getSystemSize(), metrics.length);
                    List<SystemSample> collected = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        HistoricMetric metric = metrics[i];
                        if (metric == null) {
                            continue;
                        }
                        double avgMs = nanosToMs(metric.getAverage(PERIOD_1S));
                        if (avgMs > 0) {
                            collected.add(new SystemSample(worldName, systemName(data.getSystem(i)), avgMs));
                        }
                    }
                    return collected;
                });
                if (worldSamples == null) {
                    continue;
                }
                worldSamples.sort((a, b) -> Double.compare(b.avgMs(), a.avgMs()));
                samples.addAll(worldSamples.subList(0, Math.min(perWorldLimit, worldSamples.size())));
            }
        } catch (Throwable t) {
            return List.of();
        }
        return samples;
    }

    private static <T> T onStoreThread(World world, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(STORE_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
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
