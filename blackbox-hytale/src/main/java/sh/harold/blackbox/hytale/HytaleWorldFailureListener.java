package sh.harold.blackbox.hytale;

import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

final class HytaleWorldFailureListener {
    private final BlackboxRuntime runtime;
    private final BlackboxPlugin plugin;
    private final Clock clock;
    private final System.Logger logger;

    HytaleWorldFailureListener(BlackboxRuntime runtime, BlackboxPlugin plugin, Clock clock, System.Logger logger) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void register() {
        try {
            plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class, this::onRemoveWorld);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to subscribe to RemoveWorldEvent; world-failure captures disabled.", e);
        }
    }

    private void onRemoveWorld(RemoveWorldEvent event) {
        if (event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        String scope = event.getWorld().getName();
        if (scope == null || scope.isBlank()) {
            scope = "unknown-world";
        }
        TriggerEvent trigger = new TriggerEvent(
            TriggerKind.WORLD_FAILURE,
            scope,
            clock.instant(),
            failureAttrs(event.getWorld().getFailureException())
        );
        try {
            runtime.worker().execute(() -> runtime.capture(trigger));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to schedule world-failure capture for " + scope, e);
        }
    }

    static Map<String, String> failureAttrs(Throwable failure) {
        if (failure == null) {
            return Map.of();
        }
        String error = failure.getClass().getName();
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            error += ": " + message;
        }
        StackTraceElement[] frames = failure.getStackTrace();
        StringBuilder stack = new StringBuilder();
        for (int i = 0; i < frames.length && i < 5; i++) {
            if (i > 0) {
                stack.append('\n');
            }
            stack.append("at ").append(frames[i]);
        }
        if (stack.isEmpty()) {
            return Map.of("error", error);
        }
        return Map.of("error", error, "errorStack", stack.toString());
    }
}
