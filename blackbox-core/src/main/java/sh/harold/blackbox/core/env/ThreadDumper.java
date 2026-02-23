package sh.harold.blackbox.core.env;

import java.util.Comparator;
import java.util.Map;

public final class ThreadDumper {
    private ThreadDumper() {
    }

    public static String dump() {
        Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        StringBuilder out = new StringBuilder(allStacks.size() * 256);
        out.append("threads=").append(allStacks.size()).append('\n');

        allStacks.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey().getName()))
            .forEach(entry -> {
                Thread t = entry.getKey();
                StackTraceElement[] stack = entry.getValue();
                out.append('\n');
                out.append('"').append(t.getName()).append('"');
                out.append(" #").append(t.threadId());
                out.append(" daemon=").append(t.isDaemon());
                out.append(" state=").append(t.getState());
                out.append('\n');
                for (StackTraceElement frame : stack) {
                    out.append("    at ").append(frame).append('\n');
                }
            });

        return out.toString();
    }
}
