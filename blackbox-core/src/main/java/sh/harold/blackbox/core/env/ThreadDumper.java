package sh.harold.blackbox.core.env;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Comparator;

public final class ThreadDumper {
    private ThreadDumper() {
    }

    public static String dump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        StringBuilder out = new StringBuilder(infos.length * 512);
        out.append("threads=").append(infos.length).append('\n');

        Arrays.stream(infos)
            .sorted(Comparator.comparing(ThreadInfo::getThreadName,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .forEach(info -> append(out, info));

        return out.toString();
    }

    private static void append(StringBuilder out, ThreadInfo info) {
        out.append('\n');
        out.append('"').append(info.getThreadName()).append('"');
        out.append(" #").append(info.getThreadId());
        out.append(" state=").append(info.getThreadState());
        LockInfo waitingOn = info.getLockInfo();
        if (waitingOn != null) {
            out.append("\n    waiting on ").append(waitingOn);
            if (info.getLockOwnerName() != null) {
                out.append(" owned by \"").append(info.getLockOwnerName())
                    .append("\" #").append(info.getLockOwnerId());
            }
        }
        out.append('\n');

        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int depth = 0; depth < stack.length; depth++) {
            out.append("    at ").append(stack[depth]).append('\n');
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == depth) {
                    out.append("    - locked ").append(monitor).append('\n');
                }
            }
        }

        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("    Locked ownable synchronizers:\n");
            for (LockInfo synchronizer : synchronizers) {
                out.append("    - ").append(synchronizer).append('\n');
            }
        }
    }
}
