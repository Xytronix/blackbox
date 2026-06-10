package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * A numeric plugin metric sample committed into the rolling recording via the public Blackbox
 * API: either the current value of a gauge or a counter delta since the previous sample. Read
 * back by {@code JfrTimeline} so the report can chart plugin metrics (chunks unloaded, entities
 * throttled, ...) on the same timeline as the JVM series.
 */
@Name(BlackboxPluginMetricEvent.NAME)
@Label("Plugin Metric")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxPluginMetricEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.PluginMetric";

    @Label("Name")
    public String name;

    @Label("Value")
    public double value;

    @Label("Counter")
    public boolean counter;
}
