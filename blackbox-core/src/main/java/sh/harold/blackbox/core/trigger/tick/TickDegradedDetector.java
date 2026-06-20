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

public final class TickDegradedDetector {

    private static final double REARM_FACTOR = 0.9;

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
