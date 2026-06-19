package sh.harold.blackbox.core.trigger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.incident.Severity;

/**
 * Applies cooldown and debounce policies to trigger events.
 */
public final class TriggerEngine {
    private final Clock clock;
    private final TriggerPolicy policy;
    private final Map<String, Instant> lastAcceptedByKey = new HashMap<>();
    private Instant lastAcceptedAt;

    public TriggerEngine(Clock clock, TriggerPolicy policy) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public TriggerResult evaluate(TriggerEvent event) {
        Objects.requireNonNull(event, "event");
        Instant now = event.at() == null ? clock.instant() : event.at();

        if (lastAcceptedAt != null && !policy.cooldownExemptKinds().contains(event.kind())) {
            Instant cooldownUntil = lastAcceptedAt.plus(policy.cooldown());
            if (now.isBefore(cooldownUntil)) {
                return new TriggerResult(TriggerDecision.COOLDOWN, Severity.INFO,
                    "Rejected: cooldown active");
            }
        }

        String key = event.kind() + "|" + event.scope();
        Instant lastForKey = lastAcceptedByKey.get(key);
        if (lastForKey != null) {
            Instant debounceUntil = lastForKey.plus(policy.debounce());
            if (now.isBefore(debounceUntil)) {
                return new TriggerResult(TriggerDecision.DEBOUNCE, Severity.INFO,
                    "Rejected: debounce active");
            }
        }

        TriggerResult accepted = decideAccepted(event);
        lastAcceptedAt = now;
        lastAcceptedByKey.put(key, now);
        return accepted;
    }

    private TriggerResult decideAccepted(TriggerEvent event) {
        if (event.kind() == TriggerKind.MANUAL) {
            String reason = event.attrs().get("reason");
            String headline = reason == null || reason.isBlank()
                ? "Manual capture"
                : "Manual capture: " + reason;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.INFO, headline);
        }
        if (event.kind() == TriggerKind.HEARTBEAT_STALL) {
            long stallMs = parseLong(event.attrs().get("stallMs"));
            Severity severity = Severity.INFO;
            if (stallMs >= policy.stallCriticalMs()) {
                severity = Severity.CRITICAL;
            } else if (stallMs >= policy.stallDegradedMs()) {
                severity = Severity.DEGRADED;
            }
            String headline = "Heartbeat stalled " + event.scope() + " (" + stallMs + "ms)";
            return new TriggerResult(TriggerDecision.ACCEPT, severity, headline);
        }
        if (event.kind() == TriggerKind.WORLD_FAILURE) {
            String error = event.attrs().get("error");
            String headline = error == null || error.isBlank()
                ? "World failed " + event.scope()
                : "World failed " + event.scope() + ": " + error;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.CRITICAL, headline);
        }
        if (event.kind() == TriggerKind.TICK_DEGRADED) {
            long tickAvgMs = parseLong(event.attrs().get("tickAvgMs"));
            Severity severity = Severity.INFO;
            if (tickAvgMs >= policy.tickAvgCriticalMs()) {
                severity = Severity.CRITICAL;
            } else if (tickAvgMs >= policy.tickAvgDegradedMs()) {
                severity = Severity.DEGRADED;
            }
            String headline = "Tick average degraded " + event.scope() + " (" + tickAvgMs + "ms)";
            return new TriggerResult(TriggerDecision.ACCEPT, severity, headline);
        }
        if (event.kind() == TriggerKind.DEADLOCK) {
            long threads = parseLong(event.attrs().get("threads"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.CRITICAL,
                "Deadlock detected (" + threads + " threads)");
        }
        if (event.kind() == TriggerKind.HEAP_PRESSURE) {
            long usedPct = parseLong(event.attrs().get("usedPct"));
            Severity severity = usedPct >= HEAP_CRITICAL_PCT ? Severity.CRITICAL : Severity.DEGRADED;
            return new TriggerResult(TriggerDecision.ACCEPT, severity,
                "Heap pressure sustained (" + usedPct + "% after GC)");
        }
        if (event.kind() == TriggerKind.GC_PRESSURE) {
            long gcPct = parseLong(event.attrs().get("gcPct"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "GC pressure (" + gcPct + "% of wall time in GC)");
        }
        if (event.kind() == TriggerKind.CPU_SATURATION) {
            long cpuPct = parseLong(event.attrs().get("cpuPct"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "Process CPU saturated (" + cpuPct + "% sustained)");
        }
        if (event.kind() == TriggerKind.NET_SATURATION) {
            String direction = event.attrs().getOrDefault("direction", "?");
            long mbps = parseLong(event.attrs().get("mbps"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "Network " + direction + "bound traffic sustained " + mbps + " Mbit/s");
        }
        if (event.kind() == TriggerKind.PLAYER_DROP) {
            long before = parseLong(event.attrs().get("before"));
            long after = parseLong(event.attrs().get("after"));
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED,
                "Player count dropped " + before + " to " + after);
        }
        if (event.kind() == TriggerKind.LOG_ERROR) {
            String error = event.attrs().get("error");
            String headline = error == null || error.isBlank()
                ? "Server error logged (" + event.scope() + ")"
                : "Server error: " + error;
            return new TriggerResult(TriggerDecision.ACCEPT, Severity.DEGRADED, headline);
        }
        return new TriggerResult(TriggerDecision.ACCEPT, Severity.INFO, "Capture triggered");
    }

    private static final long HEAP_CRITICAL_PCT = 97;

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
