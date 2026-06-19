package sh.harold.blackbox.core.trigger.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class GcPressureDetectorTest {

    private record Fixture(MutableClock clock, AtomicLong gcMillis, GcPressureDetector detector) {
        static Fixture create() {
            MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
            AtomicLong gcMillis = new AtomicLong(0);
            GcPressureDetector detector = new GcPressureDetector(
                clock, gcMillis::get, 0.25, Duration.ofSeconds(60));
            return new Fixture(clock, gcMillis, detector);
        }

        List<TriggerEvent> poll(int polls, long gcPerPoll) {
            List<TriggerEvent> fired = List.of();
            for (int i = 0; i < polls; i++) {
                clock.advance(Duration.ofSeconds(5));
                gcMillis.addAndGet(gcPerPoll);
                List<TriggerEvent> events = detector.check();
                if (!events.isEmpty()) {
                    fired = events;
                }
            }
            return fired;
        }
    }

    @Test
    void firesWhenGcFractionReachesThreshold() {
        Fixture f = Fixture.create();

        assertEquals(0, f.poll(12, 100).size());
        List<TriggerEvent> events = f.poll(6, 5_000);
        assertEquals(1, events.size());
        assertEquals(TriggerKind.GC_PRESSURE, events.get(0).kind());
        assertTrue(Long.parseLong(events.get(0).attrs().get("gcPct")) >= 25);

        assertEquals(0, f.poll(3, 5_000).size());
    }

    @Test
    void doesNotFireOnPartialWindowOrHealthyLoad() {
        Fixture f = Fixture.create();

        f.clock().advance(Duration.ofSeconds(10));
        f.gcMillis().addAndGet(10_000);
        assertEquals(0, f.detector().check().size());

        assertEquals(0, f.poll(30, 200).size());
    }

    @Test
    void reArmsAfterRecovery() {
        Fixture f = Fixture.create();

        assertEquals(0, f.poll(12, 100).size());
        assertEquals(1, f.poll(6, 5_000).size());

        assertEquals(0, f.poll(20, 50).size());

        assertEquals(1, f.poll(8, 5_000).size());
    }
}
