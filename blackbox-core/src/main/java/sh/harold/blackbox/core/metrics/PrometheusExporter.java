package sh.harold.blackbox.core.metrics;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

import sh.harold.blackbox.core.metrics.HealthGauges.IncidentKey;
import sh.harold.blackbox.core.metrics.HealthGauges.Sample;
import sh.harold.blackbox.core.metrics.HealthGauges.World;

public final class PrometheusExporter implements AutoCloseable {

    private final HttpServer server;

    public PrometheusExporter(HealthGauges gauges, String bind, int port, String path) throws IOException {
        Objects.requireNonNull(gauges, "gauges");
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext(path, exchange -> {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                byte[] body = render(gauges).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(null);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static String render(HealthGauges gauges) {
        Sample s = gauges.sample();
        StringBuilder sb = new StringBuilder(1024);

        gaugeD(sb, "blackbox_tps", "Server ticks per second.", s.tps());
        gaugeD(sb, "blackbox_tick_avg_ms", "Average tick duration in milliseconds.", s.tickAvgMs());
        gaugeL(sb, "blackbox_players", "Players currently online.", s.players());
        gaugeL(sb, "blackbox_heap_used_bytes", "JVM heap memory in use, in bytes.", s.heapUsedBytes());
        gaugeL(sb, "blackbox_rss_bytes", "Resident set size of the server process, in bytes.", s.rssBytes());
        gaugeD(sb, "blackbox_gc_percent", "Time spent in garbage collection, as a percentage.", s.gcPct());
        gaugeD(sb, "blackbox_cpu_percent", "Process CPU usage, as a percentage.", s.cpuPct());
        gaugeD(sb, "blackbox_alloc_mb_per_sec", "Heap allocation rate in megabytes per second.", s.allocMbPerSec());
        counterL(sb, "blackbox_cpu_usage_usec_total", "Container CPU time used, in microseconds (cgroup).", gauges.cpuUsageUsec());
        counterL(sb, "blackbox_cpu_throttled_usec_total", "Container CPU time throttled, in microseconds (cgroup).", gauges.cpuThrottledUsec());
        counterL(sb, "blackbox_cpu_throttled_periods_total", "Periods the container was CPU-throttled (cgroup).", gauges.cpuThrottledPeriods());
        gaugeL(sb, "blackbox_deadlocked_threads", "Threads involved in a detected deadlock cycle.", gauges.deadlockedThreads());
        gaugeL(sb, "blackbox_queued_packets", "Inbound packets queued across all player connections.", gauges.queuedPackets());
        gaugeD(sb, "blackbox_metrics_collector_time_ms",
            "Time spent collecting the last metrics sample, in milliseconds.", gauges.collectorTimeMs());

        List<World> worlds = s.worlds();
        worldGaugeD(sb, "blackbox_world_tps", "Ticks per second, per world.", worlds, World::tps);
        worldGaugeD(sb, "blackbox_world_tick_ms", "Average tick duration per world, in milliseconds.", worlds, World::tickMs);
        worldSeriesL(sb, "blackbox_world_players", "Players, per world.", "gauge", worlds, World::players);
        worldSeriesL(sb, "blackbox_world_entities", "Active entities, per world.", "gauge", worlds, World::entities);
        worldGaugeD(sb, "blackbox_world_avg_ping_ms", "Average player ping per world, in milliseconds.", worlds, World::avgPingMs);
        worldSeriesL(sb, "blackbox_world_chunks", "Loaded chunks, per world.", "gauge", worlds, World::chunks);
        worldSeriesL(sb, "blackbox_world_chunks_generated_total", "Cumulative chunks generated, per world.",
            "counter", worlds, World::chunksGeneratedTotal);
        worldSeriesL(sb, "blackbox_world_chunks_loaded_total", "Cumulative chunks loaded, per world.",
            "counter", worlds, World::chunksLoadedTotal);

        gaugeL(sb, "blackbox_bundles_count", "Number of incident bundle archives on disk.", gauges.bundlesCount());
        gaugeL(sb, "blackbox_bundles_bytes", "Total size of incident bundle archives on disk, in bytes.", gauges.bundlesBytes());

        pluginMetrics(sb, gauges.pluginGauges(), "blackbox_plugin_gauge", "Plugin-provided gauge metric.", "gauge");
        pluginMetrics(sb, gauges.pluginCounters(), "blackbox_plugin_counter", "Plugin-provided counter metric.", "counter");

        Map<IncidentKey, Long> incidents = gauges.incidents();
        if (!incidents.isEmpty()) {
            sb.append("# HELP blackbox_incidents_total Total incidents recorded, by trigger and severity.\n");
            sb.append("# TYPE blackbox_incidents_total counter\n");
            incidents.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<IncidentKey, Long> e) -> e.getKey().trigger())
                    .thenComparing(e -> e.getKey().severity()))
                .forEach(e -> sb.append("blackbox_incidents_total{trigger=\"")
                    .append(escape(e.getKey().trigger())).append("\",severity=\"")
                    .append(escape(e.getKey().severity())).append("\"} ")
                    .append(e.getValue()).append('\n'));
            long ts = gauges.lastIncidentEpochSeconds();
            if (ts >= 0) {
                sb.append("# HELP blackbox_last_incident_timestamp_seconds Unix timestamp of the most recent incident.\n");
                sb.append("# TYPE blackbox_last_incident_timestamp_seconds gauge\n");
                sb.append("blackbox_last_incident_timestamp_seconds ").append(ts).append('\n');
            }
        }

        Map<String, Long> heartbeats = gauges.heartbeats();
        if (!heartbeats.isEmpty()) {
            sb.append("# HELP blackbox_heartbeat_last_beat_seconds Unix timestamp of the last heartbeat, per scope.\n");
            sb.append("# TYPE blackbox_heartbeat_last_beat_seconds gauge\n");
            heartbeats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("blackbox_heartbeat_last_beat_seconds{scope=\"")
                    .append(escape(e.getKey())).append("\"} ")
                    .append(e.getValue()).append('\n'));
        }

        Map<String, Long> disconnects = gauges.disconnects();
        if (!disconnects.isEmpty()) {
            sb.append("# HELP blackbox_player_disconnects_total Player disconnects, by reason.\n");
            sb.append("# TYPE blackbox_player_disconnects_total counter\n");
            disconnects.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("blackbox_player_disconnects_total{reason=\"")
                    .append(escape(e.getKey())).append("\"} ")
                    .append(e.getValue()).append('\n'));
        }

        String version = gauges.version();
        if (version != null && !version.isBlank()) {
            sb.append("# HELP blackbox_build_info Blackbox build information.\n");
            sb.append("# TYPE blackbox_build_info gauge\n");
            sb.append("blackbox_build_info{version=\"").append(escape(version)).append("\"} 1\n");
        }

        sb.append("# HELP blackbox_up 1 when the metrics exporter is responding.\n");
        sb.append("# TYPE blackbox_up gauge\n");
        sb.append("blackbox_up 1\n");
        return sb.toString();
    }

    private static void pluginMetrics(StringBuilder sb, Map<String, Double> metrics, String name, String help, String type) {
        boolean typed = false;
        List<Map.Entry<String, Double>> entries = metrics.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .toList();
        for (Map.Entry<String, Double> e : entries) {
            double value = e.getValue();
            if (Double.isNaN(value)) {
                continue;
            }
            if (!typed) {
                sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
                sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
                typed = true;
            }
            sb.append(name).append("{name=\"").append(escape(e.getKey())).append("\"} ")
                .append(fmt(value)).append('\n');
        }
    }

    private static void gaugeD(StringBuilder sb, String name, String help, double value) {
        if (Double.isNaN(value) || value < 0) {
            return;
        }
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(fmt(value)).append('\n');
    }

    private static void gaugeL(StringBuilder sb, String name, String help, long value) {
        if (value < 0) {
            return;
        }
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void counterL(StringBuilder sb, String name, String help, long value) {
        if (value < 0) {
            return;
        }
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" counter\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void worldGaugeD(StringBuilder sb, String name, String help, List<World> worlds,
                                    ToDoubleFunction<World> field) {
        boolean typed = false;
        for (World w : worlds) {
            double value = field.applyAsDouble(w);
            if (Double.isNaN(value) || value < 0) {
                continue;
            }
            if (!typed) {
                sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
                sb.append("# TYPE ").append(name).append(" gauge\n");
                typed = true;
            }
            sb.append(name).append("{world=\"").append(escape(w.name())).append("\"} ")
                .append(fmt(value)).append('\n');
        }
    }

    private static void worldSeriesL(StringBuilder sb, String name, String help, String type,
                                     List<World> worlds, ToLongFunction<World> field) {
        boolean typed = false;
        for (World w : worlds) {
            long value = field.applyAsLong(w);
            if (value < 0) {
                continue;
            }
            if (!typed) {
                sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
                sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
                typed = true;
            }
            sb.append(name).append("{world=\"").append(escape(w.name())).append("\"} ")
                .append(value).append('\n');
        }
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
