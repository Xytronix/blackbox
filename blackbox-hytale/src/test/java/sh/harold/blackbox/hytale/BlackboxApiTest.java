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
        BlackboxApi.registerDiagnostics("My Plugin", () -> Map.of("mode", "fast"));

        List<DiagnosticSection> sections = BlackboxApi.collectDiagnostics();

        assertEquals(1, sections.size());
        assertEquals("My Plugin", sections.get(0).title());
        assertEquals(Map.of("mode", "fast"), sections.get(0).entries());
    }

    @Test
    void unregisteringDiagnosticsRemovesSection() {
        BlackboxApi.Registration registration =
            BlackboxApi.registerDiagnostics("My Plugin", () -> Map.of("mode", "fast"));

        registration.unregister();

        assertTrue(BlackboxApi.collectDiagnostics().isEmpty());
    }

    @Test
    void diagnosticsRegistrationIsCapped() {
        for (int i = 0; i < 64; i++) {
            BlackboxApi.registerDiagnostics("plugin-" + i, Map::of);
        }

        assertThrows(IllegalStateException.class,
            () -> BlackboxApi.registerDiagnostics("one-too-many", Map::of));
    }

    @Test
    void registeredGaugeIsPulledFreshEachRead() {
        double[] value = {1.0};
        BlackboxApi.registerGauge("example gauge", () -> value[0]);

        assertEquals(1.0, BlackboxApi.sampleSupplierGauges().get("example gauge"));

        value[0] = 42.0;
        assertEquals(42.0, BlackboxApi.sampleSupplierGauges().get("example gauge"));
    }

    @Test
    void unregisteringGaugeRemovesIt() {
        BlackboxApi.Registration registration = BlackboxApi.registerGauge("temp", () -> 5.0);
        assertEquals(5.0, BlackboxApi.sampleSupplierGauges().get("temp"));

        registration.unregister();

        assertFalse(BlackboxApi.sampleSupplierGauges().containsKey("temp"));
    }

    @Test
    void gaugeRegistrationIsCapped() {
        for (int i = 0; i < 64; i++) {
            BlackboxApi.registerGauge("g-" + i, () -> 1.0);
        }

        assertThrows(IllegalStateException.class,
            () -> BlackboxApi.registerGauge("one-too-many", () -> 1.0));
    }

    @Test
    void nonFiniteGaugeValueIsOmitted() {
        BlackboxApi.registerGauge("bad", () -> Double.NaN);

        assertFalse(BlackboxApi.sampleSupplierGauges().containsKey("bad"));
    }

    @Test
    void registerConfigRequiresInitialization() {
        assertThrows(IllegalStateException.class,
            () -> BlackboxApi.registerConfig(java.nio.file.Path.of("mods", "x", "config.json")));
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
