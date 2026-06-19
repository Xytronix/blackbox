package sh.harold.blackbox.core.trigger.jvm;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

public final class CpuSaturationDetector {

    private static final double REARM_FACTOR = 0.9;

    @FunctionalInterface
    public interface Source {
        double processLoad();
    }

    private final Clock clock;
    private final Source source;
    private final double threshold;
    private final Duration sustain;
    private Instant aboveSince;
    private boolean fired;

    public CpuSaturationDetector(Clock clock, Source source, double threshold, Duration sustain) {
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
        double load = source.processLoad();
        if (load < 0 || Double.isNaN(load)) {
            aboveSince = null;
            fired = false;
            return List.of();
        }
        Instant now = clock.instant();
        if (load >= threshold) {
            if (aboveSince == null) {
                aboveSince = now;
            }
            long sustainedSec = Duration.between(aboveSince, now).toSeconds();
            if (!fired && !now.isBefore(aboveSince.plus(sustain))) {
                fired = true;
                return List.of(new TriggerEvent(
                    TriggerKind.CPU_SATURATION,
                    "jvm",
                    now,
                    Map.of(
                        "cpuPct", Long.toString(Math.round(load * 100)),
                        "sustainedSec", Long.toString(sustainedSec)
                    )
                ));
            }
            return List.of();
        }
        aboveSince = null;
        if (load < threshold * REARM_FACTOR) {
            fired = false;
        }
        return List.of();
    }
}
