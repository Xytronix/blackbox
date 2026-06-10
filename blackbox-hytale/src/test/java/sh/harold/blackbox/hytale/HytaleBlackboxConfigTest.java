package sh.harold.blackbox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.blackbox.core.config.BlackboxConfig;

class HytaleBlackboxConfigTest {

    @Test
    void parsesCaptureAndDisabledEventsFields(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleBlackboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Jfr": {
                "DisabledEvents": ["sh.harold.blackbox.marker", "jdk.CPULoad"]
              },
              "Capture": {
                "Enabled": false,
                "AllowPluginExtras": false,
                "LogTailLines": 12,
                "RedactPatterns": ["CUSTOM_SECRET_[A-Z0-9]+"]
              }
            }
            """, StandardCharsets.UTF_8);

        BlackboxConfig config = HytaleBlackboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(List.of("sh.harold.blackbox.marker", "jdk.CPULoad"), config.jfrDisabledEvents());
        assertFalse(config.capturePolicy().enabled());
        assertFalse(config.capturePolicy().allowPluginExtras());
        assertEquals(12, config.capturePolicy().logTailLines());
        assertEquals(List.of("CUSTOM_SECRET_[A-Z0-9]+"), config.capturePolicy().redactPatterns());
    }

    @Test
    void defaultsNewFieldsWhenOmitted(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleBlackboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1
            }
            """, StandardCharsets.UTF_8);

        BlackboxConfig config = HytaleBlackboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertTrue(config.jfrDisabledEvents().isEmpty());
        assertTrue(config.capturePolicy().enabled());
        assertTrue(config.capturePolicy().allowPluginExtras());
        assertEquals(500, config.capturePolicy().logTailLines());
        assertFalse(config.capturePolicy().redactPatterns().isEmpty());
        assertEquals(Duration.ofSeconds(60), config.postIncidentMaxWait());
        assertEquals(Duration.ofMinutes(5), config.jfrSnapshotInterval());
        assertEquals(Duration.ofSeconds(10), config.jfrSampleInterval());
        assertEquals(100, config.triggerPolicy().tickAvgDegradedMs());
        assertEquals(250, config.triggerPolicy().tickAvgCriticalMs());
    }

    @Test
    void parsesJfrSampleInterval(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleBlackboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Jfr": {
                "SampleInterval": "PT30S"
              }
            }
            """, StandardCharsets.UTF_8);

        BlackboxConfig config = HytaleBlackboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(Duration.ofSeconds(30), config.jfrSampleInterval());
    }

    @Test
    void parsesTickAvgThresholds(@TempDir Path dataDir) throws Exception {
        Path configPath = HytaleBlackboxConfig.path(dataDir);
        Files.writeString(configPath, """
            {
              "Version": 1,
              "Trigger": {
                "TickAvgDegradedMs": 150,
                "TickAvgCriticalMs": 400
              }
            }
            """, StandardCharsets.UTF_8);

        BlackboxConfig config = HytaleBlackboxConfig.loadOrCreate(dataDir, System.getLogger("hytale-config-test"));

        assertEquals(150, config.triggerPolicy().tickAvgDegradedMs());
        assertEquals(400, config.triggerPolicy().tickAvgCriticalMs());
        assertEquals(2000, config.triggerPolicy().stallDegradedMs());
        assertEquals(10000, config.triggerPolicy().stallCriticalMs());
    }
}
