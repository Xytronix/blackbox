package sh.harold.blackbox.core.trigger.tick;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits tick-degraded trigger events on transition into a degraded state.
 *
 * <p>Edge-triggered with hysteresis like the heartbeat stall detector: fires once when a scope's
 * average tick length crosses {@code degradedMs}, then re-arms only after the average recovers
 * below {@link #REARM_FACTOR} times that threshold, so sustained lag yields a single incident
 * instead of a stream. Severity is decided from the average at the crossing (which under frequent
 * polling of a long-window average is almost always just above the degraded threshold), so an
 * episode that later worsens past the critical threshold does not re-fire or escalate — CRITICAL
 * effectively requires the first observed crossing to already exceed it.
 */
public final class TickDegradedDetector {

    /**
     * Re-arm only after the average recovers below this fraction of {@code degradedMs}. Unlike the
     * stall detector — whose time-since-beat signal is monotonic within a stall episode and cannot
     * oscillate — a borderline-loaded server's average can hover around the threshold; without this
     * margin every dip-and-rise would re-fire a full capture (JFR bundle + notification).
     */
    private static final double REARM_FACTOR = 0.9;

    /**
     * Supplies the current average tick length in milliseconds per scope. The average should cover
     * a window long enough (tens of seconds) that single slow ticks do not cross the threshold.
     */
    @FunctionalInterface
    public interface Source {
        Map<String, Double> tickAverageMillis();
    }

    private final Clock clock;
    private final Source source;
    private final long degradedMs;
    private final Map<String, Boolean> inDegraded = new HashMap<>();

    public TickDegradedDetector(Clock clock, Source source, long degradedMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        if (degradedMs <= 0) {
            throw new IllegalArgumentException("degradedMs must be > 0.");
        }
        this.degradedMs = degradedMs;
    }

    public List<TriggerEvent> check() {
        Instant now = clock.instant();
        Map<String, Double> averages = source.tickAverageMillis();
        inDegraded.keySet().retainAll(averages.keySet());
        List<TriggerEvent> events = new ArrayList<>();
        for (Map.Entry<String, Double> entry : averages.entrySet()) {
            String scope = entry.getKey();
            Double average = entry.getValue();
            if (scope == null || scope.isBlank() || average == null) {
                continue;
            }
            boolean wasDegraded = inDegraded.getOrDefault(scope, false);
            if (!wasDegraded && average >= degradedMs) {
                inDegraded.put(scope, true);
                events.add(new TriggerEvent(
                    TriggerKind.TICK_DEGRADED,
                    scope,
                    now,
                    Map.of("tickAvgMs", Long.toString(Math.round(average)))
                ));
            } else if (wasDegraded && average < degradedMs * REARM_FACTOR) {
                inDegraded.put(scope, false);
            }
        }
        return events;
    }
}
