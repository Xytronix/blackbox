package sh.harold.blackbox.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.capture.CapturePolicy;
import sh.harold.blackbox.core.notify.discord.DiscordWebhookConfig;
import sh.harold.blackbox.core.retention.RetentionPolicy;
import sh.harold.blackbox.core.trigger.TriggerPolicy;

class BlackboxConfigTest {

    private static BlackboxConfig withSampleInterval(Duration sampleInterval) {
        return new BlackboxConfig(
            Duration.ofMinutes(5),
            0L,
            "blackbox",
            List.of(),
            new TriggerPolicy(Duration.ofMinutes(1), Duration.ofSeconds(5), 2000, 10000, 100, 250),
            new CapturePolicy(new RetentionPolicy(10, 0L, null)),
            new DiscordWebhookConfig("", Duration.ofMinutes(5), Duration.ofSeconds(10), "Blackbox"),
            Duration.ZERO,
            Duration.ZERO,
            "default",
            sampleInterval
        );
    }

    @Test
    void defaultsSampleIntervalWhenNull() {
        assertEquals(Duration.ofSeconds(10), withSampleInterval(null).jfrSampleInterval());
    }

    @Test
    void clampsSampleIntervalToMinimum() {
        assertEquals(Duration.ofSeconds(5), withSampleInterval(Duration.ofSeconds(1)).jfrSampleInterval());
    }

    @Test
    void clampsSampleIntervalToMaximum() {
        assertEquals(Duration.ofMinutes(5), withSampleInterval(Duration.ofHours(1)).jfrSampleInterval());
    }

    @Test
    void keepsSampleIntervalInRange() {
        assertEquals(Duration.ofSeconds(30), withSampleInterval(Duration.ofSeconds(30)).jfrSampleInterval());
    }

    private static BlackboxConfig prometheus(int port, String bind) {
        return new BlackboxConfig(
            Duration.ofMinutes(5),
            0L,
            "blackbox",
            List.of(),
            new TriggerPolicy(Duration.ofMinutes(1), Duration.ofSeconds(5), 2000, 10000, 100, 250),
            new CapturePolicy(new RetentionPolicy(10, 0L, null)),
            new DiscordWebhookConfig("", Duration.ofMinutes(5), Duration.ofSeconds(10), "Blackbox"),
            Duration.ZERO,
            Duration.ZERO,
            "default",
            Duration.ofSeconds(10),
            false,
            true,
            7,
            true,
            false,
            true,
            port,
            bind,
            3,
            Duration.ofSeconds(10)
        );
    }

    @Test
    void defaultsPrometheusPortWhenInvalid() {
        assertEquals(9099, prometheus(0, "127.0.0.1").prometheusPort());
    }

    @Test
    void defaultsPrometheusBindWhenBlank() {
        assertEquals("127.0.0.1", prometheus(9099, "  ").prometheusBind());
    }
}
