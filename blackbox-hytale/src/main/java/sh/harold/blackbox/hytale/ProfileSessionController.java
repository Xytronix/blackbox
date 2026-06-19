package sh.harold.blackbox.hytale;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import sh.harold.blackbox.core.jfr.JfrController;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

final class ProfileSessionController {
    private final Clock clock;
    private final System.Logger logger;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;
    private final JfrController jfr;
    private final Consumer<TriggerEvent> capture;
    private final AtomicBoolean profileActive = new AtomicBoolean(false);
    private volatile long profileEndsAtMs;
    private volatile long profileStartMs;
    private volatile boolean keptBuffer;

    ProfileSessionController(Clock clock, System.Logger logger, ScheduledExecutorService scheduler,
                             ExecutorService worker, JfrController jfr, Consumer<TriggerEvent> capture) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.jfr = Objects.requireNonNull(jfr, "jfr");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    String start(int minutes, boolean keepBuffer) {
        int clamped = Math.max(1, Math.min(30, minutes));
        if (!profileActive.compareAndSet(false, true)) {
            return "A profile session is already running (" + remainingSeconds() + "s remaining).";
        }
        keptBuffer = keepBuffer;
        profileStartMs = clock.millis();
        if (!keepBuffer) {
            try {
                capture.accept(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                    Map.of("reason", "profile lead-up")));
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to capture the pre-profile lead-up.", e);
            }
            try {
                jfr.restart("profile");
            } catch (Exception e) {
                profileActive.set(false);
                return "Failed to switch the recording to the profile preset: " + e;
            }
        } else {
            jfr.applyConfiguration("profile");
        }
        profileEndsAtMs = clock.millis() + clamped * 60_000L;
        scheduler.schedule(() -> worker.execute(this::finish), clamped, TimeUnit.MINUTES);
        return null;
    }

    boolean active() {
        return profileActive.get();
    }

    long remainingSeconds() {
        return Math.max(0, (profileEndsAtMs - clock.millis()) / 1000);
    }

    private void finish() {
        try {
            capture.accept(new TriggerEvent(TriggerKind.MANUAL, "server", clock.instant(),
                Map.of("reason", "profile session", "profileStartMs", Long.toString(profileStartMs))));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Profile-session capture failed.", e);
        } finally {
            try {
                if (keptBuffer) {
                    jfr.applyConfiguration(null);
                } else {
                    jfr.restart(null);
                }
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to revert the recording preset.", e);
            }
            profileActive.set(false);
        }
    }
}
