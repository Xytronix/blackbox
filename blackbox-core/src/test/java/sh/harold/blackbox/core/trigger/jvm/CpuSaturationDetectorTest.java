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

class CpuSaturationDetectorTest {

    @Test
    void firesOnlyAfterSustainedSaturation() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> load = new AtomicReference<>(0.4);
        CpuSaturationDetector detector = new CpuSaturationDetector(
            clock, load::get, 0.95, Duration.ofSeconds(60));

        assertEquals(0, detector.check().size());

        load.set(0.99);
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(59));
        assertEquals(0, detector.check().size());

        clock.advance(Duration.ofSeconds(1));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.CPU_SATURATION, events.get(0).kind());
        assertEquals("99", events.get(0).attrs().get("cpuPct"));

        clock.advance(Duration.ofSeconds(120));
        assertEquals(0, detector.check().size());
    }

    @Test
    void reArmsBelowRecoveryMarginAndIgnoresUnknownLoad() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<Double> load = new AtomicReference<>(0.99);
        CpuSaturationDetector detector = new CpuSaturationDetector(
            clock, load::get, 0.95, Duration.ZERO);

        assertEquals(1, detector.check().size());

        load.set(0.90);
        assertEquals(0, detector.check().size());
        load.set(0.99);
        assertEquals(0, detector.check().size());

        load.set(0.3);
        assertEquals(0, detector.check().size());
        load.set(0.99);
        assertEquals(1, detector.check().size());

        load.set(-1.0);
        assertEquals(0, detector.check().size());
        load.set(0.99);
        assertEquals(1, detector.check().size());
    }
}
