package sh.harold.blackbox.core.trigger.jvm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

public final class HeapPressureDetector {

    private static final double REARM_FACTOR = 0.9;

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
