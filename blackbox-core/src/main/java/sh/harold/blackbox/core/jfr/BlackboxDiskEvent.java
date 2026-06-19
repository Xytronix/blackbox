package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(BlackboxDiskEvent.NAME)
@Label("Disk Space Sample")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxDiskEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.Disk";

    @Label("Free bytes")
    public long freeBytes;

    @Label("Total bytes")
    public long totalBytes;

    @Label("Read bytes")
    public long readBytes;

    @Label("Write bytes")
    public long writeBytes;
}
