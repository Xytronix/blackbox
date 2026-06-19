package sh.harold.blackbox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.bundle.BundleExtrasRegistry;
import sh.harold.blackbox.core.capture.BundleExtrasProvider;
import sh.harold.blackbox.core.incident.DiagnosticSection;

class BlackboxApiTest {

    @AfterEach
    void tearDown() {
        BlackboxApi.shutdown();
    }

    @Test
    void registeredDiagnosticsAreCollected() {
        BlackboxApi.registerDiagnostics("My Plugin", "Status", () -> Map.of("mode", "fast"));

        List<DiagnosticSection> sections = BlackboxApi.collectDiagnostics();

        assertEquals(1, sections.size());
        assertEquals("Status", sections.get(0).title());
        assertEquals(Map.of("mode", "fast"), sections.get(0).entries());
    }

    @Test
    void unregisteringDiagnosticsRemovesSection() {
        BlackboxApi.Registration registration =
            BlackboxApi.registerDiagnostics("My Plugin", "Status", () -> Map.of("mode", "fast"));

        registration.unregister();

        assertTrue(BlackboxApi.collectDiagnostics().isEmpty());
    }

    @Test
    void diagnosticsCapIsPerPlugin() {
        for (int i = 0; i < 64; i++) {
            BlackboxApi.registerDiagnostics("noisy", "section-" + i, Map::of);
        }

        assertThrows(IllegalStateException.class,
            () -> BlackboxApi.registerDiagnostics("noisy", "one-too-many", Map::of));

        BlackboxApi.registerDiagnostics("other", "ok", Map::of);
        assertTrue(BlackboxApi.collectDiagnostics().stream().anyMatch(s -> s.title().equals("ok")));
    }

    @Test
    void registeredGaugeIsPulledFreshEachRead() {
        double[] value = {1.0};
        BlackboxApi.registerGauge("p", "example gauge", () -> value[0]);

        assertEquals(1.0, BlackboxApi.sampleSupplierGauges().get("p/example gauge"));

        value[0] = 42.0;
        assertEquals(42.0, BlackboxApi.sampleSupplierGauges().get("p/example gauge"));
    }

    @Test
    void unregisteringGaugeRemovesIt() {
        BlackboxApi.Registration registration = BlackboxApi.registerGauge("p", "temp", () -> 5.0);
        assertEquals(5.0, BlackboxApi.sampleSupplierGauges().get("p/temp"));

        registration.unregister();

        assertFalse(BlackboxApi.sampleSupplierGauges().containsKey("p/temp"));
    }

    @Test
    void gaugeCapIsPerPlugin() {
        for (int i = 0; i < 64; i++) {
            BlackboxApi.registerGauge("noisy", "g-" + i, () -> 1.0);
        }

        assertThrows(IllegalStateException.class,
            () -> BlackboxApi.registerGauge("noisy", "one-too-many", () -> 1.0));
    }

    @Test
    void nonFiniteGaugeValueIsOmitted() {
        BlackboxApi.registerGauge("p", "bad", () -> Double.NaN);

        assertFalse(BlackboxApi.sampleSupplierGauges().containsKey("p/bad"));
    }

    @Test
    void sameMetricNameFromDifferentPluginsDoesNotCollide() {
        BlackboxApi.recordGauge("a", "requests", 1.0);
        BlackboxApi.recordGauge("b", "requests", 2.0);

        Map<String, Double> gauges = BlackboxApi.pluginGauges();
        assertEquals(1.0, gauges.get("a/requests"));
        assertEquals(2.0, gauges.get("b/requests"));
    }

    @Test
    void countersFromDifferentPluginsAccumulateSeparately() {
        BlackboxApi.recordCount("a", "hits", 3);
        BlackboxApi.recordCount("b", "hits", 5);
        BlackboxApi.recordCount("a", "hits", 2);

        Map<String, Double> counters = BlackboxApi.pluginCounters();
        assertEquals(5.0, counters.get("a/hits"));
        assertEquals(5.0, counters.get("b/hits"));
    }

    @Test
    void clearPluginRemovesOnlyThatPluginsState() {
        BlackboxApi.registerGauge("a", "g", () -> 1.0);
        BlackboxApi.recordGauge("a", "m", 2.0);
        BlackboxApi.registerDiagnostics("a", "d", Map::of);
        BlackboxApi.registerGauge("b", "g", () -> 9.0);

        BlackboxApi.clearPlugin("a");

        assertFalse(BlackboxApi.sampleSupplierGauges().containsKey("a/g"));
        assertFalse(BlackboxApi.pluginGauges().containsKey("a/m"));
        assertTrue(BlackboxApi.collectDiagnostics().isEmpty());
        assertEquals(9.0, BlackboxApi.sampleSupplierGauges().get("b/g"));
    }

    @Test
    void shutdownClearsAccumulatedMetrics() {
        BlackboxApi.recordGauge("a", "g", 1.0);
        BlackboxApi.recordCount("a", "c", 1);

        BlackboxApi.shutdown();

        assertTrue(BlackboxApi.pluginGauges().isEmpty());
        assertTrue(BlackboxApi.pluginCounters().isEmpty());
    }

    @Test
    void registerConfigNoOpsWhenUninitialized() {
        BlackboxApi.Registration registration =
            BlackboxApi.registerConfig("x", java.nio.file.Path.of("mods", "x", "config.json"));

        registration.unregister();

        assertTrue(BlackboxApi.registeredConfigPaths().isEmpty());
    }

    @Test
    void extrasRegistryUnregistersProviders() {
        BundleExtrasRegistry registry = new BundleExtrasRegistry(System.getLogger("api-test"));
        BundleExtrasProvider provider = (report, event) -> List.of();

        registry.register(provider);
        assertEquals(1, registry.size());

        registry.unregister(provider);
        assertEquals(0, registry.size());
    }
}
