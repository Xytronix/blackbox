package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Periodic per-system tick-cost sample committed by the platform adapter: the 1-second average
 * wall-clock tick time of one engine ECS system in one world, read from the engine's own
 * per-system metrics. Read back by {@code JfrTimeline} so the report can chart which systems
 * consumed the tick budget across the whole recording window.
 */
@Name(BlackboxSystemTickEvent.NAME)
@Label("System Tick Sample")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxSystemTickEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.SystemTick";

    @Label("World")
    public String world;

    @Label("System")
    public String system;

    @Label("Avg ms")
    public double avgMs;
}
