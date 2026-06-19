package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

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
