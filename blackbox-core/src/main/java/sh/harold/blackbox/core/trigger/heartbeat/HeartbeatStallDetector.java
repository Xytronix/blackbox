package sh.harold.blackbox.core.trigger.heartbeat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits heartbeat stall trigger events on transition into a stalled state.
 */
public final class HeartbeatStallDetector {
    private final Clock clock;
    private final HeartbeatRegistry registry;
    private final long degradedMs;
    private final Map<String, Boolean> inStall = new HashMap<>();
    private final Map<String, Instant> lastSeenBeat = new HashMap<>();

    public HeartbeatStallDetector(Clock clock, HeartbeatRegistry registry, long degradedMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (degradedMs <= 0) {
            throw new IllegalArgumentException("degradedMs must be > 0.");
        }
        this.degradedMs = degradedMs;
    }

    public List<TriggerEvent> check() {
        Instant now = clock.instant();
        Set<String> scopes = registry.scopes();
        inStall.keySet().retainAll(scopes);
        lastSeenBeat.keySet().retainAll(scopes);
        List<TriggerEvent> events = new ArrayList<>();
        for (String scope : scopes) {
            Instant last = registry.lastBeat(scope);
            if (last == null) {
                continue;
            }
            Instant previousBeat = lastSeenBeat.get(scope);
            if (previousBeat == null || last.isAfter(previousBeat)) {
                lastSeenBeat.put(scope, last);
                inStall.put(scope, false);
            }
            long stallMs = Duration.between(last, now).toMillis();
            boolean stalled = stallMs >= degradedMs;
            boolean wasStalled = inStall.getOrDefault(scope, false);
            if (stalled && !wasStalled) {
                inStall.put(scope, true);
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("stallMs", Long.toString(stallMs));
                Long threadId = registry.lastBeatThreadId(scope);
                if (threadId != null) {
                    attrs.put("threadId", Long.toString(threadId));
                }
                events.add(new TriggerEvent(TriggerKind.HEARTBEAT_STALL, scope, now, attrs));
            } else if (!stalled && wasStalled) {
                inStall.put(scope, false);
            }
        }
        return events;
    }
}
