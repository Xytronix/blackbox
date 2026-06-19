package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

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
