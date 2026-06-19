package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.commands.world.perf.WorldPerfCommand;

import sh.harold.blackbox.core.health.HealthSnapshot;
import sh.harold.blackbox.core.health.WorldStatsProvider;

final class HytaleWorldStatsProvider implements WorldStatsProvider {
    private static final System.Logger LOGGER = System.getLogger(HytaleWorldStatsProvider.class.getName());

    private final PlayerNameMasker nameMasker;

    HytaleWorldStatsProvider(PlayerNameMasker nameMasker) {
        this.nameMasker = java.util.Objects.requireNonNull(nameMasker, "nameMasker");
    }

    @Override
    public Worlds worlds() {
        List<HealthSnapshot.World> out = new ArrayList<>();
        int targetTps = 0;
        try {
            Map<String, World> worlds = Universe.get().getWorlds();
            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                String name = entry.getKey();
                World world = entry.getValue();
                if (name == null || name.isBlank() || world == null) {
                    continue;
                }
                double tps = -1;
                double mspt = -1;
                double[] percentiles = {-1, -1, -1};
                try {
                    long tickStepNanos = world.getTickStepNanos();
                    if (tickStepNanos > 0 && targetTps == 0) {
                        targetTps = (int) Math.round(1_000_000_000.0 / tickStepNanos);
                    }
                    HistoricMetric metrics = world.getBufferedTickLengthMetricSet();
                    double avgDeltaNanos = metrics.getAverage(0);
                    tps = WorldPerfCommand.tpsFromDelta(avgDeltaNanos, tickStepNanos);
                    mspt = avgDeltaNanos / 1_000_000.0;
                    percentiles = msptPercentiles(metrics);
                } catch (Exception ignored) {
                }
                out.add(new HealthSnapshot.World(
                    name, playerCount(world), entityCount(world), chunkCount(world), tps, mspt,
                    playerNames(world), avgPingMs(world),
                    percentiles[0], percentiles[1], percentiles[2]));
            }
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING, "World stats collection failed.", e);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new Worlds(targetTps, out);
    }

    private static double[] msptPercentiles(HistoricMetric metrics) {
        try {
            long[] values = metrics.getValues(2);
            if (values == null || values.length == 0) {
                return new double[] {-1, -1, -1};
            }
            double[] ms = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                ms[i] = values[i] / 1_000_000.0;
            }
            java.util.Arrays.sort(ms);
            double p50 = ms[(int) Math.min(ms.length - 1, Math.floor(ms.length * 0.50))];
            double p95 = ms[(int) Math.min(ms.length - 1, Math.floor(ms.length * 0.95))];
            return new double[] {p50, p95, ms[ms.length - 1]};
        } catch (Throwable t) {
            return new double[] {-1, -1, -1};
        }
    }

    private List<String> playerNames(World world) {
        try {
            List<String> names = new ArrayList<>();
            for (com.hypixel.hytale.server.core.universe.PlayerRef ref : world.getPlayerRefs()) {
                if (ref == null) {
                    continue;
                }
                String username = ref.getUsername();
                if (username != null && !username.isBlank()) {
                    nameMasker.remember(username, ref.getUuid());
                    names.add(nameMasker.maskName(username));
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static double avgPingMs(World world) {
        try {
            double totalMicros = 0;
            int count = 0;
            for (com.hypixel.hytale.server.core.universe.PlayerRef ref : world.getPlayerRefs()) {
                if (ref == null) {
                    continue;
                }
                try {
                    double micros = ref.getPacketHandler()
                        .getPingInfo(com.hypixel.hytale.protocol.packets.connection.PongType.Raw)
                        .getPingMetricSet().getAverage(0);
                    if (micros > 0) {
                        totalMicros += micros;
                        count++;
                    }
                } catch (Throwable ignored) {
                }
            }
            return count == 0 ? -1 : (totalMicros / count) / 1000.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int playerCount(World world) {
        try {
            return world.getPlayerCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int entityCount(World world) {
        try {
            return world.getEntityStore().getStore().getEntityCount();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int chunkCount(World world) {
        try {
            return world.getChunkStore().getLoadedChunksCount();
        } catch (Exception e) {
            return -1;
        }
    }
}
