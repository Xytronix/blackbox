package sh.harold.blackbox.core.trigger.jvm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits a heap-pressure trigger event when the after-GC occupancy of the tenured heap stays at or
 * above a threshold for a sustained period. After-GC occupancy is the honest OOM-risk signal: a
 * heap that the collector cannot shrink below the threshold is genuinely full, while pre-GC spikes
 * are normal allocation behaviour. Edge-triggered with hysteresis like the tick detector: fires
 * once per episode and re-arms only after occupancy recovers below {@link #REARM_FACTOR} times
 * the threshold.
 */
public final class HeapPressureDetector {

    private static final double REARM_FACTOR = 0.9;

    /** Supplies the after-GC used fraction (0..1) of the tenured pool, or NaN when unknown. */
    @FunctionalInterface
    public interface Source {
        double usedFraction();
    }

    private final Clock clock;
    private final Source source;
    private final double threshold;
    private final Duration sustain;
    private Instant aboveSince;
    private boolean fired;

    public HeapPressureDetector(Clock clock, Source source, double threshold, Duration sustain) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sustain, "sustain");
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be within (0, 1].");
        }
        if (sustain.isNegative()) {
            throw new IllegalArgumentException("sustain must be >= 0.");
        }
        this.threshold = threshold;
        this.sustain = sustain;
    }

    public List<TriggerEvent> check() {
        double used = source.usedFraction();
        if (Double.isNaN(used)) {
            aboveSince = null;
            fired = false;
            return List.of();
        }
        Instant now = clock.instant();
        if (used >= threshold) {
            if (aboveSince == null) {
                aboveSince = now;
            }
            long sustainedSec = Duration.between(aboveSince, now).toSeconds();
            if (!fired && !now.isBefore(aboveSince.plus(sustain))) {
                fired = true;
                return List.of(new TriggerEvent(
                    TriggerKind.HEAP_PRESSURE,
                    "jvm",
                    now,
                    Map.of(
                        "usedPct", Long.toString(Math.round(used * 100)),
                        "sustainedSec", Long.toString(sustainedSec)
                    )
                ));
            }
            return List.of();
        }
        aboveSince = null;
        if (used < threshold * REARM_FACTOR) {
            fired = false;
        }
        return List.of();
    }
}
