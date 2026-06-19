package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(BlackboxConnectionEvent.NAME)
@Label("Connection")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxConnectionEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.Connection";

    @Label("Player")
    public String player;

    /** connect | join | leave | setup disconnect */
    @Label("Phase")
    public String phase;

    /** World name on join, disconnect reason on leave/setup disconnect. */
    @Label("Detail")
    public String detail;
}
