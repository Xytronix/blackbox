package sh.harold.blackbox.core.trigger.jvm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits a GC-pressure trigger event when the fraction of wall time spent in GC over a rolling
 * window reaches a threshold. The input is the cumulative collection time of all collectors, so
 * the fraction is exact over the window regardless of poll jitter. Edge-triggered with hysteresis:
 * fires once per episode and re-arms after the fraction recovers below {@link #REARM_FACTOR}
 * times the threshold. Requires roughly a full window of samples before it can fire, so a burst
 * right after startup cannot trip it on a sliver of data.
 */
public final class GcPressureDetector {

    private static final double REARM_FACTOR = 0.9;
    /** Fraction of the window that must be covered by samples before the detector may fire. */
    private static final double MIN_WINDOW_COVERAGE = 0.8;

    /** Supplies cumulative GC time in milliseconds across all collectors, or -1 when unknown. */
    @FunctionalInterface
    public interface Source {
        long gcTimeMillis();
    }

    private record Sample(Instant at, long gcMillis) {}

    private final Clock clock;
    private final Source source;
    private final double threshold;
    private final Duration window;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private boolean fired;

    public GcPressureDetector(Clock clock, Source source, double threshold, Duration window) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(window, "window");
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be within (0, 1].");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be > 0.");
        }
        this.threshold = threshold;
        this.window = window;
    }

    public List<TriggerEvent> check() {
        long gcMillis = source.gcTimeMillis();
        if (gcMillis < 0) {
            samples.clear();
            fired = false;
            return List.of();
        }
        Instant now = clock.instant();
        samples.addLast(new Sample(now, gcMillis));
        Instant cutoff = now.minus(window);
        while (!samples.isEmpty() && samples.peekFirst().at().isBefore(cutoff)) {
            samples.removeFirst();
        }

        Sample oldest = samples.peekFirst();
        long wallMillis = Duration.between(oldest.at(), now).toMillis();
        if (wallMillis < window.toMillis() * MIN_WINDOW_COVERAGE) {
            return List.of();
        }
        double fraction = Math.max(0, gcMillis - oldest.gcMillis()) / (double) wallMillis;
        if (!fired && fraction >= threshold) {
            fired = true;
            return List.of(new TriggerEvent(
                TriggerKind.GC_PRESSURE,
                "jvm",
                now,
                Map.of("gcPct", Long.toString(Math.round(fraction * 100)))
            ));
        }
        if (fired && fraction < threshold * REARM_FACTOR) {
            fired = false;
        }
        return List.of();
    }
}
