package sh.harold.blackbox.core.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HeapHistogramTest {

    @Test
    void parsesJcmdHistogramOutput() {
        String raw = " num     #instances         #bytes  class name (module)\n"
            + "-------------------------------------------------------\n"
            + "   1:       1234567      987654321  [B (java.base@25)\n"
            + "   2:        234567       23456789  java.lang.String (java.base@25)\n"
            + "   3:         34567        3456789  com.hypixel.hytale.server.core.universe.world.chunk.Chunk\n"
            + "Total      1503701     1014567899\n";

        Map<String, String> entries = HeapHistogram.parse(raw);

        assertEquals(3, entries.size());
        assertEquals("1234567 987654321", entries.get("[B"));
        assertEquals("234567 23456789", entries.get("java.lang.String"));
        assertEquals("34567 3456789", entries.get("com.hypixel.hytale.server.core.universe.world.chunk.Chunk"));
        assertEquals("[B", entries.keySet().iterator().next());
    }

    @Test
    void capsAtThirtyRows() {
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            raw.append("   ").append(i).append(":       100      2000  com.example.Type").append(i).append('\n');
        }

        Map<String, String> entries = HeapHistogram.parse(raw.toString());

        assertEquals(30, entries.size());
        assertTrue(entries.containsKey("com.example.Type30"));
        assertFalse(entries.containsKey("com.example.Type31"));
    }

    @Test
    void emptyOnBlankOrMalformedInput() {
        assertTrue(HeapHistogram.parse(null).isEmpty());
        assertTrue(HeapHistogram.parse("").isEmpty());
        assertTrue(HeapHistogram.parse("no histogram here\njust text\n").isEmpty());
    }

    @Test
    void formatsTopLinesWithHumanBytes() {
        String raw = "   1:       1234567      123456789  [B (java.base@25)\n"
            + "   2:        234567       23456789  java.lang.String (java.base@25)\n"
            + "   3:           512            900  com.example.Tiny\n";

        var lines = HeapHistogram.formatLines(HeapHistogram.parse(raw), 2);

        assertEquals(2, lines.size());
        assertEquals("[B  instances=1234567  bytes=117.7 MiB", lines.get(0));
        assertEquals("java.lang.String  instances=234567  bytes=22.4 MiB", lines.get(1));
    }

    @Test
    void formatLinesIsEmptyOnEmptyInput() {
        assertTrue(HeapHistogram.formatLines(Map.of(), 10).isEmpty());
    }
}
