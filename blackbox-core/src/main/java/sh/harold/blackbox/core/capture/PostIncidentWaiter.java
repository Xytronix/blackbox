package sh.harold.blackbox.core.capture;

import sh.harold.blackbox.core.trigger.TriggerEvent;

@FunctionalInterface
public interface PostIncidentWaiter {

    void awaitResolution(TriggerEvent event);

    static PostIncidentWaiter none() {
        return event -> { };
    }
}
