package sh.harold.blackbox.core.trigger.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class HeapPressureDetectorTest {

    @Test
    void firesOnlyAfterSustainedPressure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> used = new AtomicReference<>(0.5);
        HeapPressureDetector detector = new HeapPressureDetector(
            clock, used::get, 0.90, Duration.ofSeconds(60));

        assertEquals(0, detector.check().size());

        used.set(0.93);
        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofSeconds(30));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.HEAP_PRESSURE, events.get(0).kind());
        assertEquals("93", events.get(0).attrs().get("usedPct"));
        assertEquals("60", events.get(0).attrs().get("sustainedSec"));

        clock.advance(Duration.ofSeconds(60));
        assertEquals(0, detector.check().size());
    }

    @Test
    void dipResetsTheSustainWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> used = new AtomicReference<>(0.95);
        HeapPressureDetector detector = new HeapPressureDetector(
            clock, used::get, 0.90, Duration.ofSeconds(60));

        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(40));
        assertEquals(0, detector.check().size());

        used.set(0.5);
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.check().size());

        used.set(0.95);
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(59));
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1, detector.check().size());
    }

    @Test
    void reArmsOnlyBelowRecoveryMargin() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> used = new AtomicReference<>(0.95);
        HeapPressureDetector detector = new HeapPressureDetector(
            clock, used::get, 0.90, Duration.ZERO);

        assertEquals(1, detector.check().size());

        used.set(0.85);
        assertEquals(0, detector.check().size());
        used.set(0.95);
        assertEquals(0, detector.check().size());

        used.set(0.7);
        assertEquals(0, detector.check().size());
        used.set(0.95);
        assertEquals(1, detector.check().size());
    }

    @Test
    void unknownReadingResetsState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> used = new AtomicReference<>(0.95);
        HeapPressureDetector detector = new HeapPressureDetector(
            clock, used::get, 0.90, Duration.ofSeconds(60));

        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(59));
        assertEquals(0, detector.check().size());

        used.set(Double.NaN);
        assertEquals(0, detector.check().size());

        used.set(0.95);
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(60));
        assertEquals(1, detector.check().size());
    }
}
