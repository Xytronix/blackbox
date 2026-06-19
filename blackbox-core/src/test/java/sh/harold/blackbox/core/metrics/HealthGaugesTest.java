package sh.harold.blackbox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthGaugesTest {

    @Test
    void freshGaugesHaveNoSampleAndNoIncidents() {
        HealthGauges gauges = new HealthGauges();
        assertEquals(List.of(), gauges.sample().worlds());
        assertEquals(-1L, gauges.lastIncidentEpochSeconds());
        assertEquals(0, gauges.incidents().size());
    }

    @Test
    void setReplacesLatestSample() {
        HealthGauges gauges = new HealthGauges();
        HealthGauges.Sample s = new HealthGauges.Sample(20, 10, 3, 1, 1, 0, 0, 0, List.of());
        gauges.set(s);
        assertSame(s, gauges.sample());
    }

    @Test
    void incrementIncidentCountsPerTriggerAndSeverity() {
        HealthGauges gauges = new HealthGauges();
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 100L);
        gauges.incrementIncident("HEAP_PRESSURE", "critical", 200L);
        gauges.incrementIncident("DEADLOCK", "critical", 300L);

        assertEquals(2L, gauges.incidents().get(new HealthGauges.IncidentKey("HEAP_PRESSURE", "critical")));
        assertEquals(1L, gauges.incidents().get(new HealthGauges.IncidentKey("DEADLOCK", "critical")));
        assertEquals(300L, gauges.lastIncidentEpochSeconds());
    }

    @Test
    void setBundlesExposesCountAndBytes() {
        HealthGauges gauges = new HealthGauges();
        gauges.setBundles(25, 1048576L);
        assertEquals(25L, gauges.bundlesCount());
        assertEquals(1048576L, gauges.bundlesBytes());
    }

    @Test
    void setVersionIsRetained() {
        HealthGauges gauges = new HealthGauges();
        gauges.setVersion("0.3");
        assertEquals("0.3", gauges.version());
    }

    @Test
    void setPluginMetricsCopiesGaugesAndCounters() {
        HealthGauges gauges = new HealthGauges();
        gauges.setPluginMetrics(Map.of("g", 1.0), Map.of("c", 2.0));
        assertEquals(Map.of("g", 1.0), gauges.pluginGauges());
        assertEquals(Map.of("c", 2.0), gauges.pluginCounters());
    }
}
