package sh.harold.blackbox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrometheusExporterTest {

    private static HealthGauges.Sample sample(List<HealthGauges.World> worlds) {
        return new HealthGauges.Sample(19.98, 12.40, 7, 1409286144L, 1879048192L,
            1.30, 42.10, 18.50, worlds);
    }

    @Test
    void render_emitsAggregateGaugesWithTypes() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of()));
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("# TYPE blackbox_tps gauge"), text);
        assertTrue(text.contains("\nblackbox_tps 19.98\n"), text);
        assertTrue(text.contains("\nblackbox_tick_avg_ms 12.40\n"), text);
        assertTrue(text.contains("\nblackbox_players 7\n"), text);
        assertTrue(text.contains("\nblackbox_heap_used_bytes 1409286144\n"), text);
        assertTrue(text.contains("\nblackbox_rss_bytes 1879048192\n"), text);
        assertTrue(text.contains("\nblackbox_gc_percent 1.30\n"), text);
        assertTrue(text.contains("\nblackbox_cpu_percent 42.10\n"), text);
        assertTrue(text.contains("\nblackbox_alloc_mb_per_sec 18.50\n"), text);
    }

    @Test
    void render_alwaysEmitsUp() {
        String text = PrometheusExporter.render(new HealthGauges());
        assertTrue(text.contains("\nblackbox_up 1\n"), text);
    }

    @Test
    void render_omitsUnknownGauges() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(new HealthGauges.Sample(-1, -1, -1, -1, -1, -1, -1, Double.NaN, List.of()));
        String text = PrometheusExporter.render(gauges);

        assertFalse(text.contains("blackbox_tps"), text);
        assertFalse(text.contains("blackbox_players"), text);
        assertFalse(text.contains("blackbox_alloc_mb_per_sec"), text);
        assertTrue(text.contains("\nblackbox_up 1\n"), text);
    }

    @Test
    void render_emitsPerWorldLabelledGauges() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of(
            new HealthGauges.World("overworld", 19.98, 12.40, 5, 320, 41.5, 1024, -1, -1))));
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("\nblackbox_world_tps{world=\"overworld\"} 19.98\n"), text);
        assertTrue(text.contains("\nblackbox_world_tick_ms{world=\"overworld\"} 12.40\n"), text);
        assertTrue(text.contains("\nblackbox_world_players{world=\"overworld\"} 5\n"), text);
        assertTrue(text.contains("\nblackbox_world_entities{world=\"overworld\"} 320\n"), text);
        assertTrue(text.contains("\nblackbox_world_avg_ping_ms{world=\"overworld\"} 41.50\n"), text);
        assertTrue(text.contains("\nblackbox_world_chunks{world=\"overworld\"} 1024\n"), text);
    }

    @Test
    void render_escapesWorldLabelValues() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of(
            new HealthGauges.World("a\"b\\c", 1.0, -1, -1, -1, -1, -1, -1, -1))));
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("blackbox_world_tps{world=\"a\\\"b\\\\c\"} 1.00\n"), text);
    }

    @Test
    void render_emitsPerWorldChurnCounters() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of(
            new HealthGauges.World("overworld", 19.98, 12.40, 5, 320, 41.5, 1024, 8000, 12000))));
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("# TYPE blackbox_world_chunks_generated_total counter"), text);
        assertTrue(text.contains("\nblackbox_world_chunks_generated_total{world=\"overworld\"} 8000\n"), text);
        assertTrue(text.contains("# TYPE blackbox_world_chunks_loaded_total counter"), text);
        assertTrue(text.contains("\nblackbox_world_chunks_loaded_total{world=\"overworld\"} 12000\n"), text);
    }

    @Test
    void render_omitsChurnCountersWhenUnknown() {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of(
            new HealthGauges.World("overworld", 19.98, 12.40, 5, 320, 41.5, 1024, -1, -1))));
        String text = PrometheusExporter.render(gauges);

        assertFalse(text.contains("blackbox_world_chunks_generated_total"), text);
        assertFalse(text.contains("blackbox_world_chunks_loaded_total"), text);
    }

    @Test
    void render_emitsCollectorTime() {
        HealthGauges gauges = new HealthGauges();
        gauges.setCollectorTimeMs(2.50);
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("# TYPE blackbox_metrics_collector_time_ms gauge"), text);
        assertTrue(text.contains("\nblackbox_metrics_collector_time_ms 2.50\n"), text);
    }

    @Test
    void render_omitsCollectorTimeWhenUnknown() {
        String text = PrometheusExporter.render(new HealthGauges());
        assertFalse(text.contains("blackbox_metrics_collector_time_ms"), text);
    }

    @Test
    void render_emitsIncidentCounterAndLastTimestamp() {
        HealthGauges gauges = new HealthGauges();
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 1718539200L);
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 1718539260L);
        String text = PrometheusExporter.render(gauges);

        assertTrue(text.contains("# TYPE blackbox_incidents_total counter"), text);
        assertTrue(text.contains(
            "\nblackbox_incidents_total{trigger=\"HEAP_PRESSURE\",severity=\"critical\"} 2\n"), text);
        assertTrue(text.contains("\nblackbox_last_incident_timestamp_seconds 1718539260\n"), text);
    }

    @Test
    void render_omitsLastTimestampWhenNoIncidents() {
        String text = PrometheusExporter.render(new HealthGauges());
        assertFalse(text.contains("blackbox_last_incident_timestamp_seconds"), text);
    }

    @Test
    void render_emitsBuildInfoWhenVersionSet() {
        HealthGauges gauges = new HealthGauges();
        gauges.setVersion("0.3");
        String text = PrometheusExporter.render(gauges);
        assertTrue(text.contains("# TYPE blackbox_build_info gauge"), text);
        assertTrue(text.contains("\nblackbox_build_info{version=\"0.3\"} 1\n"), text);
    }

    @Test
    void render_emitsBundleStats() {
        HealthGauges gauges = new HealthGauges();
        gauges.setBundles(25, 1048576L);
        String text = PrometheusExporter.render(gauges);
        assertTrue(text.contains("\nblackbox_bundles_count 25\n"), text);
        assertTrue(text.contains("\nblackbox_bundles_bytes 1048576\n"), text);
    }

    @Test
    void render_omitsBundleStatsWhenUnknown() {
        String text = PrometheusExporter.render(new HealthGauges());
        assertFalse(text.contains("blackbox_bundles_count"), text);
    }

    @Test
    void render_emitsPluginGaugesAndCounters() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("Entities frozen", 42.0), Map.of("Chunks unloaded", 180.0));
        String text = PrometheusExporter.render(gauges);
        assertTrue(text.contains("# TYPE blackbox_plugin_gauge gauge"), text);
        assertTrue(text.contains("\nblackbox_plugin_gauge{name=\"Entities frozen\"} 42.00\n"), text);
        assertTrue(text.contains("# TYPE blackbox_plugin_counter counter"), text);
        assertTrue(text.contains("\nblackbox_plugin_counter{name=\"Chunks unloaded\"} 180.00\n"), text);
    }

    @Test
    void render_keepsPluginKeyVerbatimInLabel() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("ai.throttled/sec", 5.0), Map.of());
        String text = PrometheusExporter.render(gauges);
        assertTrue(text.contains("\nblackbox_plugin_gauge{name=\"ai.throttled/sec\"} 5.00\n"), text);
    }

    @Test
    void render_distinguishesCollidingPluginKeysByLabel() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("ai.throttled/sec", 5.0, "ai_throttled_sec", 9.0), Map.of());
        String text = PrometheusExporter.render(gauges);
        assertEquals(1, text.lines().filter(l -> l.equals("# TYPE blackbox_plugin_gauge gauge")).count(), text);
        assertTrue(text.contains("\nblackbox_plugin_gauge{name=\"ai.throttled/sec\"} 5.00\n"), text);
        assertTrue(text.contains("\nblackbox_plugin_gauge{name=\"ai_throttled_sec\"} 9.00\n"), text);
    }

    @Test
    void render_sameKeyAsGaugeAndCounterDoesNotConflict() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("queue depth", 3.0), Map.of("queue depth", 7.0));
        String text = PrometheusExporter.render(gauges);
        assertTrue(text.contains("\nblackbox_plugin_gauge{name=\"queue depth\"} 3.00\n"), text);
        assertTrue(text.contains("\nblackbox_plugin_counter{name=\"queue depth\"} 7.00\n"), text);
    }

    @Test
    void httpServer_servesRenderedMetricsAtPath() throws Exception {
        HealthGauges gauges = new HealthGauges();
        gauges.set(sample(List.of()));
        try (PrometheusExporter exporter = new PrometheusExporter(gauges, "127.0.0.1", 0, "/metrics")) {
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + exporter.port() + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("blackbox_tps 19.98"), resp.body());
            assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/plain"),
                resp.headers().toString());
        }
    }
}
