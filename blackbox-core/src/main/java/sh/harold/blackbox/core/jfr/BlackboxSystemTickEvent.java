package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

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
