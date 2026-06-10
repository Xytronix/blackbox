package sh.harold.blackbox.core.trigger.net;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits a network-saturation trigger event when inbound or outbound throughput stays at or above
 * its configured threshold for a sustained period. This is the closest reliable stand-in for a
 * "DDoS" trigger: traffic flooding is observable, the attacker's intent is not, so the event is
 * named for what is actually measured. Thresholds are host-specific; a direction with threshold
 * {@code <= 0} is disabled. Edge-triggered with hysteresis per direction, like the other
 * sustained detectors.
 */
public final class NetSaturationDetector {

    private static final double REARM_FACTOR = 0.9;

    /** Latest observed throughput per direction in Mbit/s; null when unknown. */
    public record Rates(double inMbps, double outMbps) {}

    /** Supplies the current throughput, or null when no measurement is available. */
    @FunctionalInterface
    public interface Source {
        Rates rates();
    }

    private final class Direction {
        private final String name;
        private final double thresholdMbps;
        private Instant aboveSince;
        private boolean fired;

        private Direction(String name, double thresholdMbps) {
            this.name = name;
            this.thresholdMbps = thresholdMbps;
        }

        private TriggerEvent check(Instant now, double mbps) {
            if (thresholdMbps <= 0) {
                return null;
            }
            if (mbps >= thresholdMbps) {
                if (aboveSince == null) {
                    aboveSince = now;
                }
                if (!fired && !now.isBefore(aboveSince.plus(sustain))) {
                    fired = true;
                    return new TriggerEvent(
                        TriggerKind.NET_SATURATION,
                        "net",
                        now,
                        Map.of("direction", name, "mbps", Long.toString(Math.round(mbps)))
                    );
                }
                return null;
            }
            aboveSince = null;
            if (mbps < thresholdMbps * REARM_FACTOR) {
                fired = false;
            }
            return null;
        }
    }

    private final Clock clock;
    private final Source source;
    private final Duration sustain;
    private final Direction in;
    private final Direction out;

    public NetSaturationDetector(Clock clock, Source source, double inThresholdMbps,
                                 double outThresholdMbps, Duration sustain) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
        this.sustain = Objects.requireNonNull(sustain, "sustain");
        if (sustain.isNegative()) {
            throw new IllegalArgumentException("sustain must be >= 0.");
        }
        this.in = new Direction("in", inThresholdMbps);
        this.out = new Direction("out", outThresholdMbps);
    }

    public List<TriggerEvent> check() {
        Rates rates = source.rates();
        if (rates == null) {
            return List.of();
        }
        Instant now = clock.instant();
        List<TriggerEvent> events = new ArrayList<>(2);
        TriggerEvent inbound = in.check(now, rates.inMbps());
        if (inbound != null) {
            events.add(inbound);
        }
        TriggerEvent outbound = out.check(now, rates.outMbps());
        if (outbound != null) {
            events.add(outbound);
        }
        return events;
    }
}
