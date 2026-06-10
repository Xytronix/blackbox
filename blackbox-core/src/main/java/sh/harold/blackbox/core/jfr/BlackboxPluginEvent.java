package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * A notable plugin-reported moment (e.g. "AiTickThrottler throttled 42 entities") committed into
 * the rolling recording via the public Blackbox API. Read back by {@code JfrTimeline} so the
 * report can place plugin activity on the chart timelines next to errors and GC events.
 */
@Name(BlackboxPluginEvent.NAME)
@Label("Plugin Event")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxPluginEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.PluginEvent";

    @Label("Category")
    public String category;

    @Label("Message")
    public String message;
}
