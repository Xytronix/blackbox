package sh.harold.blackbox.core.trigger.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class TickDegradedDetectorTest {

    @Test
    void emitsOncePerDegradedTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Double> averages = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> averages, 100);

        averages.put("world", 50.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 150.0);
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.TICK_DEGRADED, events.get(0).kind());
        assertEquals("world", events.get(0).scope());
        assertEquals("150", events.get(0).attrs().get("tickAvgMs"));

        averages.put("world", 300.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 80.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 120.0);
        assertEquals(1, detector.check().size());
    }

    @Test
    void requiresRecoveryMarginBeforeReArming() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Double> averages = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> averages, 100);

        averages.put("world", 150.0);
        assertEquals(1, detector.check().size());

        averages.put("world", 95.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 120.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 80.0);
        assertEquals(0, detector.check().size());

        averages.put("world", 120.0);
        assertEquals(1, detector.check().size());
    }

    @Test
    void clearsStateForRemovedScope() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        Map<String, Double> averages = new HashMap<>();
        TickDegradedDetector detector = new TickDegradedDetector(clock, () -> averages, 100);

        averages.put("world", 150.0);
        assertEquals(1, detector.check().size());

        averages.clear();
        assertEquals(0, detector.check().size());

        averages.put("world", 150.0);
        assertEquals(1, detector.check().size());
    }
}
