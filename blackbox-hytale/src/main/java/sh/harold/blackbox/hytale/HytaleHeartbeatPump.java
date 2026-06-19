package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    static void awaitRecovery(
        TriggerEvent event,
        HeartbeatRegistry registry,
        Clock clock,
        Duration maxWait,
        System.Logger logger
    ) {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return;
        }
        if (event.kind() != TriggerKind.HEARTBEAT_STALL) {
            Instant tailDeadline = clock.instant().plus(maxWait);
            while (clock.instant().isBefore(tailDeadline)) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            logger.log(System.Logger.Level.INFO,
                "Recorded a " + maxWait.toSeconds() + "s post-incident tail for " + event.kind()
                + "; dumping the recording.");
            return;
        }
        String scope = event.scope();
        if (scope == null || scope.isBlank()) {
            return;
        }
        Instant triggerAt = event.at();
        Instant deadline = clock.instant().plus(maxWait);
        while (clock.instant().isBefore(deadline)) {
            Instant last = registry.lastBeat(scope);
            if (last != null && last.isAfter(triggerAt)) {
                logger.log(System.Logger.Level.INFO,
                    "Heartbeat for '" + scope + "' resumed; dumping recording with post-incident recovery.");
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.log(System.Logger.Level.INFO,
            "Heartbeat for '" + scope + "' did not resume within " + maxWait.toSeconds()
            + "s; dumping the recording now.");
    }
}
