package sh.harold.blackbox.core.trigger.heartbeat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;

class HeartbeatStallDetectorTest {

    @Test
    void emitsOncePerStallTransition() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");

        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofMillis(1100));
        assertEquals(1, detector.check().size());

        clock.advance(Duration.ofMillis(200));
        assertEquals(0, detector.check().size());

        registry.beat("world");
        clock.advance(Duration.ofMillis(1200));
        assertEquals(1, detector.check().size());
    }

    @Test
    void doesNotEmitForRemovedScope() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");
        registry.retain(Set.of());

        clock.advance(Duration.ofMillis(1100));
        assertEquals(0, detector.check().size());
    }

    @Test
    void includesBeatingThreadIdInEvent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        HeartbeatRegistry registry = new HeartbeatRegistry(clock);
        HeartbeatStallDetector detector = new HeartbeatStallDetector(clock, registry, 1000);

        registry.beat("world");
        clock.advance(Duration.ofMillis(1100));

        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(Long.toString(Thread.currentThread().threadId()), events.get(0).attrs().get("threadId"));
    }
}
