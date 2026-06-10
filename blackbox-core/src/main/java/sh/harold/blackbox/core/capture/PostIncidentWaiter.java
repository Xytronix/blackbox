package sh.harold.blackbox.core.capture;

import sh.harold.blackbox.core.trigger.TriggerEvent;

/**
 * Optionally blocks between the incident-moment health snapshot and the JFR dump so the dumped
 * recording also covers what happened <em>after</em> the trigger (for example, until the stalled
 * scope's heartbeat resumes), capped at a maximum wait.
 *
 * <p>Implementations must be best-effort and must not throw: a wait is never allowed to abort a
 * capture. Returning immediately (the {@link #none()} default) preserves the crash-safe behaviour
 * of dumping at the instant of the trigger.
 */
@FunctionalInterface
public interface PostIncidentWaiter {

    void awaitResolution(TriggerEvent event);

    static PostIncidentWaiter none() {
        return event -> { };
    }
}
