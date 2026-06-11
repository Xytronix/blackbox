package sh.harold.blackbox.core.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.Severity;
import sh.harold.blackbox.core.testutil.MutableClock;

class TriggerEngineTest {

    @Test
    void cooldownAndDebounceApplyInOrder() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        TriggerPolicy policy = new TriggerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60), 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);

        TriggerEvent event = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        TriggerResult first = engine.evaluate(event);
        assertEquals(TriggerDecision.ACCEPT, first.decision());

        TriggerResult second = engine.evaluate(event);
        assertEquals(TriggerDecision.COOLDOWN, second.decision());

        clock.advance(Duration.ofSeconds(31));
        TriggerEvent laterEvent = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        TriggerResult third = engine.evaluate(laterEvent);
        assertEquals(TriggerDecision.DEBOUNCE, third.decision());

        clock.advance(Duration.ofSeconds(30));
        TriggerEvent finalEvent = new TriggerEvent(TriggerKind.MANUAL, "world", clock.instant(), Map.of());
        TriggerResult fourth = engine.evaluate(finalEvent);
        assertEquals(TriggerDecision.ACCEPT, fourth.decision());
    }

    @Test
    void worldFailureBypassesGlobalCooldownButNotDebounce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        TriggerPolicy policy = new TriggerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(2), 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);

        TriggerEvent stall = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "world", clock.instant(),
            Map.of("stallMs", "2000"));
        assertEquals(TriggerDecision.ACCEPT, engine.evaluate(stall).decision());

        clock.advance(Duration.ofSeconds(5));
        TriggerEvent failure = new TriggerEvent(TriggerKind.WORLD_FAILURE, "world", clock.instant(),
            Map.of("error", "java.lang.IllegalStateException: boom"));
        TriggerResult accepted = engine.evaluate(failure);
        assertEquals(TriggerDecision.ACCEPT, accepted.decision());
        assertEquals(Severity.CRITICAL, accepted.severity());

        clock.advance(Duration.ofSeconds(1));
        TriggerEvent repeat = new TriggerEvent(TriggerKind.WORLD_FAILURE, "world", clock.instant(),
            Map.of("error", "java.lang.IllegalStateException: boom"));
        assertEquals(TriggerDecision.DEBOUNCE, engine.evaluate(repeat).decision());

        clock.advance(Duration.ofSeconds(1));
        TriggerEvent otherScope = new TriggerEvent(TriggerKind.WORLD_FAILURE, "other", clock.instant(),
            Map.of("error", "java.lang.IllegalStateException: boom"));
        assertEquals(TriggerDecision.ACCEPT, engine.evaluate(otherScope).decision());

        clock.advance(Duration.ofSeconds(1));
        TriggerEvent secondStall = new TriggerEvent(TriggerKind.HEARTBEAT_STALL, "other2", clock.instant(),
            Map.of("stallMs", "2000"));
        assertEquals(TriggerDecision.COOLDOWN, engine.evaluate(secondStall).decision());
    }

    @Test
    void worldFailureIsCritical() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        TriggerPolicy policy = new TriggerPolicy(Duration.ofSeconds(30), Duration.ofSeconds(2), 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);

        TriggerEvent event = new TriggerEvent(TriggerKind.WORLD_FAILURE, "world", clock.instant(),
            Map.of("error", "java.lang.IllegalStateException: boom"));
        TriggerResult result = engine.evaluate(event);
        assertEquals(TriggerDecision.ACCEPT, result.decision());
        assertEquals(Severity.CRITICAL, result.severity());
        assertEquals("World failed world: java.lang.IllegalStateException: boom", result.headline());
    }

    @Test
    void tickDegradedSeverityFollowsThresholds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        TriggerPolicy policy = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);

        TriggerEvent degraded = new TriggerEvent(TriggerKind.TICK_DEGRADED, "world", clock.instant(),
            Map.of("tickAvgMs", "120"));
        TriggerResult first = engine.evaluate(degraded);
        assertEquals(TriggerDecision.ACCEPT, first.decision());
        assertEquals(Severity.DEGRADED, first.severity());
        assertEquals("Tick average degraded world (120ms)", first.headline());

        clock.advance(Duration.ofSeconds(1));
        TriggerEvent critical = new TriggerEvent(TriggerKind.TICK_DEGRADED, "other", clock.instant(),
            Map.of("tickAvgMs", "300"));
        TriggerResult second = engine.evaluate(critical);
        assertEquals(TriggerDecision.ACCEPT, second.decision());
        assertEquals(Severity.CRITICAL, second.severity());
    }

    @Test
    void healthDetectorKindsMapToSeverities() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-11T00:00:00Z"), ZoneOffset.UTC);
        TriggerPolicy policy = new TriggerPolicy(Duration.ZERO, Duration.ZERO, 1000, 5000, 100, 250);
        TriggerEngine engine = new TriggerEngine(clock, policy);

        TriggerResult deadlock = engine.evaluate(new TriggerEvent(
            TriggerKind.DEADLOCK, "jvm", clock.instant(), Map.of("threads", "2", "threadIds", "7,12")));
        assertEquals(Severity.CRITICAL, deadlock.severity());
        assertEquals("Deadlock detected (2 threads)", deadlock.headline());

        TriggerResult heapDegraded = engine.evaluate(new TriggerEvent(
            TriggerKind.HEAP_PRESSURE, "jvm", clock.instant(), Map.of("usedPct", "92", "sustainedSec", "60")));
        assertEquals(Severity.DEGRADED, heapDegraded.severity());

        TriggerResult heapCritical = engine.evaluate(new TriggerEvent(
            TriggerKind.HEAP_PRESSURE, "jvm2", clock.instant(), Map.of("usedPct", "98", "sustainedSec", "60")));
        assertEquals(Severity.CRITICAL, heapCritical.severity());

        TriggerResult gc = engine.evaluate(new TriggerEvent(
            TriggerKind.GC_PRESSURE, "jvm", clock.instant(), Map.of("gcPct", "31")));
        assertEquals(Severity.DEGRADED, gc.severity());
        assertEquals("GC pressure (31% of wall time in GC)", gc.headline());

        TriggerResult cpu = engine.evaluate(new TriggerEvent(
            TriggerKind.CPU_SATURATION, "jvm", clock.instant(), Map.of("cpuPct", "97", "sustainedSec", "60")));
        assertEquals(Severity.DEGRADED, cpu.severity());

        TriggerResult net = engine.evaluate(new TriggerEvent(
            TriggerKind.NET_SATURATION, "net", clock.instant(), Map.of("direction", "in", "mbps", "940")));
        assertEquals(Severity.DEGRADED, net.severity());
        assertEquals("Network inbound traffic sustained 940 Mbit/s", net.headline());

        TriggerResult drop = engine.evaluate(new TriggerEvent(
            TriggerKind.PLAYER_DROP, "server", clock.instant(), Map.of("before", "20", "after", "4")));
        assertEquals(Severity.DEGRADED, drop.severity());
        assertEquals("Player count dropped 20 to 4", drop.headline());
    }
}
