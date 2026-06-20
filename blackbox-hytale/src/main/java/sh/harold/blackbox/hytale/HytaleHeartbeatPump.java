package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import sh.harold.blackbox.core.env.ThreadDumper;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatRegistry;

final class HytaleHeartbeatPump {
    private final System.Logger logger;
    private final HeartbeatRegistry heartbeatRegistry;
    private final Map<String, AtomicBoolean> heartbeatPending = new ConcurrentHashMap<>();
    private int heartbeatSweepCounter;

    HytaleHeartbeatPump(System.Logger logger, HeartbeatRegistry heartbeatRegistry) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.heartbeatRegistry = Objects.requireNonNull(heartbeatRegistry, "heartbeatRegistry");
    }

    void tick() {
        try {
            Universe universe = Universe.get();
            Map<String, World> worlds = universe.getWorlds();
            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                String scope = entry.getKey();
                if (scope == null || scope.isBlank()) {
                    continue;
                }
                World world = entry.getValue();
                if (world == null) {
                    continue;
                }

                AtomicBoolean pending = heartbeatPending.computeIfAbsent(scope, ignored -> new AtomicBoolean(false));
                if (!pending.compareAndSet(false, true)) {
                    continue;
                }

                try {
                    world.execute(() -> {
                        try {
                            heartbeatRegistry.beat(scope);
                        } catch (Exception e) {
                            logger.log(System.Logger.Level.WARNING, "Failed to beat heartbeat for " + scope, e);
                        } finally {
                            pending.set(false);
                        }
                    });
                } catch (Exception e) {
                    pending.set(false);
                    logger.log(System.Logger.Level.WARNING, "Failed to post heartbeat for " + scope, e);
                }
            }

            heartbeatRegistry.retain(worlds.keySet());

            heartbeatSweepCounter++;
            if (heartbeatSweepCounter >= 100) {
                heartbeatSweepCounter = 0;
                heartbeatPending.keySet().removeIf(scope -> !worlds.containsKey(scope));
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Heartbeat tick failed.", e);
        }
    }

    static List<String> awaitRecovery(
        TriggerEvent event,
        HeartbeatRegistry registry,
        Clock clock,
        Duration maxWait,
        int dumpCount,
        Duration dumpInterval,
        System.Logger logger
    ) {
        List<String> dumps = new ArrayList<>();
        boolean heartbeat = event.kind() == TriggerKind.HEARTBEAT_STALL;
        String scope = event.scope();
        if (heartbeat && (scope == null || scope.isBlank())) {
            return dumps;
        }
        int maxDumps = Math.max(1, dumpCount);
        dumps.add(ThreadDumper.dump());
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return dumps;
        }
        Instant triggerAt = event.at();
        Instant deadline = clock.instant().plus(maxWait);
        boolean spacing = dumpInterval != null && !dumpInterval.isZero() && !dumpInterval.isNegative();
        Instant nextDump = spacing ? clock.instant().plus(dumpInterval) : null;
        while (clock.instant().isBefore(deadline)) {
            if (heartbeat) {
                Instant last = registry.lastBeat(scope);
                if (last != null && last.isAfter(triggerAt)) {
                    logger.log(System.Logger.Level.INFO,
                        "Heartbeat for '" + scope + "' resumed; dumping recording with post-incident recovery.");
                    return dumps;
                }
            }
            if (nextDump != null && dumps.size() < maxDumps && !clock.instant().isBefore(nextDump)) {
                dumps.add(ThreadDumper.dump());
                nextDump = clock.instant().plus(dumpInterval);
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dumps;
            }
        }
        if (heartbeat) {
            logger.log(System.Logger.Level.INFO,
                "Heartbeat for '" + scope + "' did not resume within " + maxWait.toSeconds()
                + "s; dumping the recording now.");
        } else {
            logger.log(System.Logger.Level.INFO,
                "Recorded a " + maxWait.toSeconds() + "s post-incident tail for " + event.kind()
                + "; dumping the recording.");
        }
        return dumps;
    }
}
