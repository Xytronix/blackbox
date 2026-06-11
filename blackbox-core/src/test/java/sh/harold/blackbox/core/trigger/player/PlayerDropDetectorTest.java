package sh.harold.blackbox.core.trigger.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.testutil.MutableClock;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

class PlayerDropDetectorTest {

    @Test
    void firesOnMassDropWithinWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        PlayerDropDetector detector = new PlayerDropDetector(clock, 0.5, Duration.ofSeconds(60), 8);

        assertEquals(0, detector.sample(20).size());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.sample(19).size());

        clock.advance(Duration.ofSeconds(10));
        List<TriggerEvent> events = detector.sample(4);
        assertEquals(1, events.size());
        assertEquals(TriggerKind.PLAYER_DROP, events.get(0).kind());
        assertEquals("20", events.get(0).attrs().get("before"));
        assertEquals("4", events.get(0).attrs().get("after"));

        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.sample(4).size());
    }

    @Test
    void smallServersNeverFire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        PlayerDropDetector detector = new PlayerDropDetector(clock, 0.5, Duration.ofSeconds(60), 8);

        assertEquals(0, detector.sample(6).size());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.sample(0).size());
    }

    @Test
    void gradualDeclineOutsideWindowDoesNotFire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        PlayerDropDetector detector = new PlayerDropDetector(clock, 0.5, Duration.ofSeconds(60), 8);

        int players = 20;
        for (int i = 0; i < 16; i++) {
            assertEquals(0, detector.sample(players).size());
            clock.advance(Duration.ofSeconds(70));
            players -= 1;
        }
    }

    @Test
    void reArmsAfterRecovery() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        PlayerDropDetector detector = new PlayerDropDetector(clock, 0.5, Duration.ofSeconds(60), 8);

        detector.sample(20);
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, detector.sample(2).size());

        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.sample(18).size());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(0, detector.sample(19).size());

        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, detector.sample(3).size());
    }
}
