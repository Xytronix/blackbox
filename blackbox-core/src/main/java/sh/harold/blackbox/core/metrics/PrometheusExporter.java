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

        gaugeD(sb, "blackbox_tps", s.tps());
        gaugeD(sb, "blackbox_tick_avg_ms", s.tickAvgMs());
        gaugeL(sb, "blackbox_players", s.players());
        gaugeL(sb, "blackbox_heap_used_bytes", s.heapUsedBytes());
        gaugeL(sb, "blackbox_rss_bytes", s.rssBytes());
        gaugeD(sb, "blackbox_gc_percent", s.gcPct());
        gaugeD(sb, "blackbox_cpu_percent", s.cpuPct());
        gaugeD(sb, "blackbox_alloc_mb_per_sec", s.allocMbPerSec());

        List<World> worlds = s.worlds();
        worldGaugeD(sb, "blackbox_world_tps", worlds, World::tps);
        worldGaugeD(sb, "blackbox_world_tick_ms", worlds, World::tickMs);
        worldGaugeL(sb, "blackbox_world_players", worlds, World::players);
        worldGaugeL(sb, "blackbox_world_entities", worlds, World::entities);
        worldGaugeD(sb, "blackbox_world_avg_ping_ms", worlds, World::avgPingMs);
        worldGaugeL(sb, "blackbox_world_chunks", worlds, World::chunks);

        gaugeL(sb, "blackbox_bundles_count", gauges.bundlesCount());
        gaugeL(sb, "blackbox_bundles_bytes", gauges.bundlesBytes());

        pluginMetrics(sb, gauges.pluginGauges(), "gauge");
        pluginMetrics(sb, gauges.pluginCounters(), "counter");

        Map<IncidentKey, Long> incidents = gauges.incidents();
        if (!incidents.isEmpty()) {
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
                sb.append("# TYPE blackbox_last_incident_timestamp_seconds gauge\n");
                sb.append("blackbox_last_incident_timestamp_seconds ").append(ts).append('\n');
            }
        }

        String version = gauges.version();
        if (version != null && !version.isBlank()) {
            sb.append("# TYPE blackbox_build_info gauge\n");
            sb.append("blackbox_build_info{version=\"").append(escape(version)).append("\"} 1\n");
        }

        sb.append("# TYPE blackbox_up gauge\n");
        sb.append("blackbox_up 1\n");
        return sb.toString();
    }

    private static void pluginMetrics(StringBuilder sb, Map<String, Double> metrics, String type) {
        metrics.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                double value = e.getValue();
                if (Double.isNaN(value)) {
                    return;
                }
                String name = "blackbox_plugin_" + sanitize(e.getKey());
                sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
                sb.append(name).append(' ').append(fmt(value)).append('\n');
            });
    }

    private static String sanitize(String name) {
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
            b.append(ok ? c : '_');
        }
        return b.toString();
    }

    private static void gaugeD(StringBuilder sb, String name, double value) {
        if (Double.isNaN(value) || value < 0) {
            return;
        }
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(fmt(value)).append('\n');
    }

    private static void gaugeL(StringBuilder sb, String name, long value) {
        if (value < 0) {
            return;
        }
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static void worldGaugeD(StringBuilder sb, String name, List<World> worlds,
                                    ToDoubleFunction<World> field) {
        boolean typed = false;
        for (World w : worlds) {
            double value = field.applyAsDouble(w);
            if (Double.isNaN(value) || value < 0) {
                continue;
            }
            if (!typed) {
                sb.append("# TYPE ").append(name).append(" gauge\n");
                typed = true;
            }
            sb.append(name).append("{world=\"").append(escape(w.name())).append("\"} ")
                .append(fmt(value)).append('\n');
        }
    }

    private static void worldGaugeL(StringBuilder sb, String name, List<World> worlds,
                                    ToLongFunction<World> field) {
        boolean typed = false;
        for (World w : worlds) {
            long value = field.applyAsLong(w);
            if (value < 0) {
                continue;
            }
            if (!typed) {
                sb.append("# TYPE ").append(name).append(" gauge\n");
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
