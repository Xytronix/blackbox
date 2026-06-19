package sh.harold.blackbox.core.capture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.DiagnosticSection;

class MemoryPoolsTest {

    @Test
    void collectsPoolsWithFourLongFields() {
        Map<String, String> pools = MemoryPools.collect();

        assertFalse(pools.isEmpty());
        for (Map.Entry<String, String> e : pools.entrySet()) {
            String[] parts = e.getValue().split(" ");
            assertEquals(4, parts.length, e.getKey());
            for (String part : parts) {
                assertDoesNotThrow(() -> Long.parseLong(part));
            }
        }
    }

    @Test
    void appendToAddsSection() {
        List<DiagnosticSection> out = MemoryPools.appendTo(List.of());

        assertEquals(1, out.size());
        assertEquals("Memory pools", out.get(0).title());
        assertFalse(out.get(0).entries().isEmpty());
    }
}
