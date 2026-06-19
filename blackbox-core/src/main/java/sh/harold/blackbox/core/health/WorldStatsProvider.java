package sh.harold.blackbox.core.health;

import java.util.List;

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
