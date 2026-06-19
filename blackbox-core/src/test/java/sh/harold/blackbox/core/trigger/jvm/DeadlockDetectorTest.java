package sh.harold.blackbox.core.trigger.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class DeadlockDetectorTest {

    @Test
    void firesOncePerDeadlockSet() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<long[]> ids = new AtomicReference<>(null);
        DeadlockDetector detector = new DeadlockDetector(clock, ids::get);

        assertEquals(0, detector.check().size());

        ids.set(new long[] {7, 12});
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.DEADLOCK, events.get(0).kind());
        assertEquals("2", events.get(0).attrs().get("threads"));
        assertEquals("7,12", events.get(0).attrs().get("threadIds"));

        assertEquals(0, detector.check().size());

        ids.set(new long[] {7, 12, 99});
        assertEquals(1, detector.check().size());
    }

    @Test
    void clearedDeadlockReArmsForTheSameSet() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<long[]> ids = new AtomicReference<>(new long[] {1, 2});
        DeadlockDetector detector = new DeadlockDetector(clock, ids::get);

        assertEquals(1, detector.check().size());

        ids.set(new long[0]);
        assertEquals(0, detector.check().size());

        ids.set(new long[] {1, 2});
        assertEquals(1, detector.check().size());
    }
}
