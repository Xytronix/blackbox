package sh.harold.blackbox.core.trigger.net;

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

class NetSaturationDetectorTest {

    @Test
    void firesPerDirectionAfterSustainedTraffic() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<NetSaturationDetector.Rates> rates =
            new AtomicReference<>(new NetSaturationDetector.Rates(10, 10));
        NetSaturationDetector detector = new NetSaturationDetector(
            clock, rates::get, 500, 800, Duration.ofSeconds(30));

        assertEquals(0, detector.check().size());

        rates.set(new NetSaturationDetector.Rates(900, 10));
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(30));
        List<TriggerEvent> events = detector.check();
        assertEquals(1, events.size());
        assertEquals(TriggerKind.NET_SATURATION, events.get(0).kind());
        assertEquals("in", events.get(0).attrs().get("direction"));
        assertEquals("900", events.get(0).attrs().get("mbps"));

        rates.set(new NetSaturationDetector.Rates(900, 950));
        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofSeconds(30));
        List<TriggerEvent> outbound = detector.check();
        assertEquals(1, outbound.size());
        assertEquals("out", outbound.get(0).attrs().get("direction"));
    }

    @Test
    void disabledDirectionNeverFires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<NetSaturationDetector.Rates> rates =
            new AtomicReference<>(new NetSaturationDetector.Rates(10_000, 10_000));
        NetSaturationDetector detector = new NetSaturationDetector(
            clock, rates::get, 0, 0, Duration.ZERO);

        assertEquals(0, detector.check().size());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(0, detector.check().size());
    }

    @Test
    void reArmsBelowRecoveryMargin() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        AtomicReference<NetSaturationDetector.Rates> rates =
            new AtomicReference<>(new NetSaturationDetector.Rates(600, 0));
        NetSaturationDetector detector = new NetSaturationDetector(
            clock, rates::get, 500, 0, Duration.ZERO);

        assertEquals(1, detector.check().size());

        rates.set(new NetSaturationDetector.Rates(480, 0));
        assertEquals(0, detector.check().size());
        rates.set(new NetSaturationDetector.Rates(600, 0));
        assertEquals(0, detector.check().size());

        rates.set(new NetSaturationDetector.Rates(100, 0));
        assertEquals(0, detector.check().size());
        rates.set(new NetSaturationDetector.Rates(600, 0));
        assertEquals(1, detector.check().size());
    }
}
