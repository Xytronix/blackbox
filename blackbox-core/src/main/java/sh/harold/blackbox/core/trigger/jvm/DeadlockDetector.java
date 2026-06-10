package sh.harold.blackbox.core.trigger.jvm;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

/**
 * Emits a deadlock trigger event when the JVM reports a deadlocked thread set. Exact signal:
 * {@code ThreadMXBean.findDeadlockedThreads()} has no false positives. Fires once per distinct
 * deadlock set; the same set is never re-reported on later polls, and the state clears when the
 * deadlock disappears (e.g. the threads were killed), so a recurring identical cycle fires again.
 */
public final class DeadlockDetector {

    /** Supplies the deadlocked thread ids, or null/empty when there is no deadlock. */
    @FunctionalInterface
    public interface Source {
        long[] deadlockedThreadIds();
    }

    private final Clock clock;
    private final Source source;
    private Set<Long> lastSet = Set.of();

    public DeadlockDetector(Clock clock, Source source) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.source = Objects.requireNonNull(source, "source");
    }

    public List<TriggerEvent> check() {
        long[] ids = source.deadlockedThreadIds();
        if (ids == null || ids.length == 0) {
            lastSet = Set.of();
            return List.of();
        }
        Set<Long> current = Arrays.stream(ids).boxed().collect(Collectors.toUnmodifiableSet());
        if (current.equals(lastSet)) {
            return List.of();
        }
        lastSet = current;
        String joined = current.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        return List.of(new TriggerEvent(
            TriggerKind.DEADLOCK,
            "jvm",
            clock.instant(),
            Map.of("threads", Integer.toString(current.size()), "threadIds", joined)
        ));
    }
}
