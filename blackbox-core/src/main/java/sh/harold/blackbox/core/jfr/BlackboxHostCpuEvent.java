package sh.harold.blackbox.core.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

@Name(BlackboxHostCpuEvent.NAME)
@Label("Host CPU Sample")
@Category("Blackbox")
@StackTrace(false)
public final class BlackboxHostCpuEvent extends Event {
    public static final String NAME = "sh.harold.blackbox.HostCpu";

    @Label("Throttled periods")
    public long throttledPeriods;

    @Label("Throttled micros")
    public long throttledMicros;

    @Label("CPU total jiffies")
    public long cpuTotalJiffies;

    @Label("CPU steal jiffies")
    public long cpuStealJiffies;

    @Label("CPU iowait jiffies")
    public long cpuIowaitJiffies;

    @Label("Container CPU usage micros")
    public long cpuUsageUsec;
}
