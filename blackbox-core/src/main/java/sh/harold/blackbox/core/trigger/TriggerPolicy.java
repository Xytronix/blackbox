package sh.harold.blackbox.core.trigger;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Defines trigger cooldowns and stall thresholds.
 */
public record TriggerPolicy(
    Duration cooldown,
    Duration debounce,
    long stallDegradedMs,
    long stallCriticalMs,
    long tickAvgDegradedMs,
    long tickAvgCriticalMs,
    DetectorPolicy detectors,
    Set<TriggerKind> cooldownExemptKinds
) {
    public static final Set<TriggerKind> DEFAULT_COOLDOWN_EXEMPT_KINDS =
        Set.of(TriggerKind.WORLD_FAILURE, TriggerKind.DEADLOCK, TriggerKind.HEARTBEAT_STALL);

    public TriggerPolicy {
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(debounce, "debounce");
        detectors = detectors == null ? DetectorPolicy.defaults() : detectors;
        cooldownExemptKinds = cooldownExemptKinds == null
            ? DEFAULT_COOLDOWN_EXEMPT_KINDS
            : Set.copyOf(cooldownExemptKinds);
        if (cooldown.isNegative()) {
            throw new IllegalArgumentException("cooldown must be non-negative.");
        }
        if (debounce.isNegative()) {
            throw new IllegalArgumentException("debounce must be non-negative.");
        }
        if (stallDegradedMs <= 0 || stallCriticalMs <= 0) {
            throw new IllegalArgumentException("stall thresholds must be > 0.");
        }
        if (stallCriticalMs < stallDegradedMs) {
            throw new IllegalArgumentException("stallCriticalMs must be >= stallDegradedMs.");
        }
        if (tickAvgDegradedMs <= 0 || tickAvgCriticalMs <= 0) {
            throw new IllegalArgumentException("tick average thresholds must be > 0.");
        }
        if (tickAvgCriticalMs < tickAvgDegradedMs) {
            throw new IllegalArgumentException("tickAvgCriticalMs must be >= tickAvgDegradedMs.");
        }
    }

    public TriggerPolicy(
        Duration cooldown,
        Duration debounce,
        long stallDegradedMs,
        long stallCriticalMs,
        long tickAvgDegradedMs,
        long tickAvgCriticalMs
    ) {
        this(cooldown, debounce, stallDegradedMs, stallCriticalMs,
            tickAvgDegradedMs, tickAvgCriticalMs, DetectorPolicy.defaults(), DEFAULT_COOLDOWN_EXEMPT_KINDS);
    }
}
