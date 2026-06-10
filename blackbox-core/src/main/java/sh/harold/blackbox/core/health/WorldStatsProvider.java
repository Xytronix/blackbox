package sh.harold.blackbox.core.health;

import java.util.List;

/**
 * Supplies per-world stats for the health snapshot. The core has no world concept,
 * so a platform module wires this in. Returns target ticks-per-second and the worlds.
 */
@FunctionalInterface
public interface WorldStatsProvider {
    Worlds worlds();

    record Worlds(int targetTps, List<HealthSnapshot.World> worlds) {
        public Worlds {
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }
    }

    static WorldStatsProvider none() {
        return () -> new Worlds(0, List.of());
    }
}
