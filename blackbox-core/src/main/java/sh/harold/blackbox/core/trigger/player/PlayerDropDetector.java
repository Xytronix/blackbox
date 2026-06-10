package sh.harold.blackbox.core.trigger.player;

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
 * Emits a player-drop trigger event when the online player count falls by a configured fraction
 * of its recent peak within a rolling window. Catches mass disconnects (network outage, login
 * backend failure, crash loop) while ignoring normal churn: the peak must reach
 * {@code minPlayers} before a drop can ever fire, so small servers emptying out at night do not
 * produce incidents. Fed by the platform's existing player sampler rather than polling a source.
 */
public final class PlayerDropDetector {

    private record Sample(Instant at, int players) {}

    private final Clock clock;
    private final double dropFraction;
    private final Duration window;
    private final int minPlayers;
    private final Deque<Sample> history = new ArrayDeque<>();
    private boolean fired;

    public PlayerDropDetector(Clock clock, double dropFraction, Duration window, int minPlayers) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(window, "window");
        if (dropFraction <= 0 || dropFraction > 1) {
            throw new IllegalArgumentException("dropFraction must be within (0, 1].");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be > 0.");
        }
        if (minPlayers < 0) {
            throw new IllegalArgumentException("minPlayers must be >= 0.");
        }
        this.dropFraction = dropFraction;
        this.window = window;
        this.minPlayers = minPlayers;
    }

    /** Records the current total player count; returns a trigger event on a mass drop. */
    public List<TriggerEvent> sample(int players) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        while (!history.isEmpty() && history.peekFirst().at().isBefore(cutoff)) {
            history.removeFirst();
        }

        int peak = 0;
        for (Sample sample : history) {
            peak = Math.max(peak, sample.players());
        }
        history.addLast(new Sample(now, players));

        boolean dropped = peak >= minPlayers && players <= peak * (1.0 - dropFraction);
        if (dropped && !fired) {
            fired = true;
            return List.of(new TriggerEvent(
                TriggerKind.PLAYER_DROP,
                "server",
                now,
                Map.of("before", Integer.toString(peak), "after", Integer.toString(players))
            ));
        }
        if (!dropped) {
            fired = false;
        }
        return List.of();
    }
}
