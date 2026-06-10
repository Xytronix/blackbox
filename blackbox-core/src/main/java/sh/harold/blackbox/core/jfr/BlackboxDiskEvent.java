package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Periodic free-space sample for the filesystem holding the server directory, committed by the
 * platform sampler. JFR records file I/O but never disk capacity, and a full disk is the one
 * failure that silently kills both the recording and bundle writes.
 */
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
