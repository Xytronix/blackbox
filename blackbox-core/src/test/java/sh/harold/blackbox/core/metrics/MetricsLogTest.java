package sh.harold.blackbox.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricsLogTest {

    @Test
    void formatRow_emitsAllColumns() {
        String row = MetricsLog.formatRow(Instant.parse("2026-06-10T20:30:00Z"),
            45.5, 19.8, 12, 1234L, 5678L, 2.5, 12.5, 3.2);
        assertEquals("2026-06-10T20:30:00Z,45.50,19.80,12,1234,5678,2.50,12.50,3.20", row);
    }

    @Test
    void formatRow_leavesUnknownGaugesBlank() {
        String row = MetricsLog.formatRow(Instant.parse("2026-06-10T20:30:00Z"),
            -1, -1, -1, -1, -1, -1, -1, -1);
        assertEquals("2026-06-10T20:30:00Z,,,,,,,,", row);
    }

    @Test
    void append_writesHeaderOncePerDayFile(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant t = Instant.parse("2026-06-10T20:30:00Z");
        log.append(t, MetricsLog.formatRow(t, 50, 20, 1, 1, 1, 0, 0, 0), 7);
        log.append(t.plusSeconds(10), MetricsLog.formatRow(t.plusSeconds(10), 51, 20, 1, 1, 1, 0, 0, 0), 7);

        Path file = dir.resolve("health-20260610.csv");
        List<String> lines = Files.readAllLines(file);
        assertEquals(MetricsLog.HEADER, lines.get(0));
        assertEquals(3, lines.size());
    }

    @Test
    void append_rollsToNewFilePerUtcDay(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant day1 = Instant.parse("2026-06-10T23:59:00Z");
        Instant day2 = Instant.parse("2026-06-11T00:01:00Z");
        log.append(day1, MetricsLog.formatRow(day1, 50, 20, 1, 1, 1, 0, 0, 0), 7);
        log.append(day2, MetricsLog.formatRow(day2, 50, 20, 1, 1, 1, 0, 0, 0), 7);

        assertTrue(Files.exists(dir.resolve("health-20260610.csv")));
        assertTrue(Files.exists(dir.resolve("health-20260611.csv")));
    }

    @Test
    void append_prunesFilesOlderThanRetention(@TempDir Path dir) throws Exception {
        MetricsLog log = new MetricsLog(dir);
        Instant old = Instant.parse("2026-06-01T12:00:00Z");
        log.append(old, MetricsLog.formatRow(old, 50, 20, 1, 1, 1, 0, 0, 0), 7);
        assertTrue(Files.exists(dir.resolve("health-20260601.csv")));

        Instant now = old.plus(Duration.ofDays(10));
        log.append(now, MetricsLog.formatRow(now, 50, 20, 1, 1, 1, 0, 0, 0), 7);

        assertFalse(Files.exists(dir.resolve("health-20260601.csv")));
        assertTrue(Files.exists(dir.resolve("health-20260611.csv")));
    }
}
