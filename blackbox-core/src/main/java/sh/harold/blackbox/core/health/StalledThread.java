package sh.harold.blackbox.core.health;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

public final class StalledThread {
    private static final int MAX_FRAMES = 40;

    private StalledThread() {
    }

    public static List<DiagnosticSection> prependTo(List<DiagnosticSection> base, TriggerEvent event,
                                                    Collection<String> frameworkPrefixes) {
        List<DiagnosticSection> sections = build(event, frameworkPrefixes);
        if (sections.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(sections.size() + base.size());
        out.addAll(sections);
        out.addAll(base);
        return out;
    }

    static List<DiagnosticSection> build(TriggerEvent event, Collection<String> frameworkPrefixes) {
        if (event == null) {
            return List.of();
        }
        long[] ids = threadIds(event);
        if (ids.length == 0) {
            return List.of();
        }
        String title = sectionTitle(event.kind());
        List<DiagnosticSection> sections = new ArrayList<>(ids.length);
        for (long id : ids) {
            DiagnosticSection section = section(title, id, event, frameworkPrefixes);
            if (section != null) {
                sections.add(section);
            }
        }
        return sections;
    }

    private static long[] threadIds(TriggerEvent event) {
        String raw = event.attrs().get("threadId");
        if (raw == null) {
            raw = event.attrs().get("threadIds");
        }
        if (raw == null || raw.isBlank()) {
            return new long[0];
        }
        String[] parts = raw.split(",");
        long[] ids = new long[parts.length];
        int count = 0;
        for (String part : parts) {
            try {
                ids[count] = Long.parseLong(part.trim());
                count++;
            } catch (NumberFormatException ignored) {
                continue;
            }
        }
        return java.util.Arrays.copyOf(ids, count);
    }

    private static String sectionTitle(TriggerKind kind) {
        return switch (kind) {
            case HEARTBEAT_STALL -> "Stalled thread";
            case DEADLOCK -> "Deadlocked thread";
            default -> "Thread";
        };
    }

    private static DiagnosticSection section(String title, long id, TriggerEvent event,
                                             Collection<String> frameworkPrefixes) {
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(id, MAX_FRAMES);
        if (info == null) {
            return null;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Thread", info.getThreadName());
        entries.put("State", info.getThreadState().toString());
        if (info.getLockName() != null) {
            entries.put("Waiting on", info.getLockName());
        }
        if (info.getLockOwnerName() != null) {
            entries.put("Lock owner", info.getLockOwnerName() + " (#" + info.getLockOwnerId() + ")");
        }
        String suspect = topSuspectFrame(info.getStackTrace(), frameworkPrefixes);
        if (suspect != null) {
            entries.put("Blocked in", suspect);
        }
        String stallMs = event.attrs().get("stallMs");
        if (stallMs != null) {
            entries.put("Stalled for", stallMs + " ms");
        }
        return new DiagnosticSection(title, entries, renderStack(info.getStackTrace()));
    }

    static String topSuspectFrame(StackTraceElement[] stack, Collection<String> frameworkPrefixes) {
        String lockSite = null;
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
                || cls.startsWith("sh.harold.blackbox.")) {
                continue;
            }
            String name = frame.getClassName() + "." + frame.getMethodName();
            if (lockSite == null) {
                lockSite = name;
            }
            if (!matchesPrefix(cls, frameworkPrefixes)) {
                return name;
            }
        }
        return lockSite;
    }

    private static boolean matchesPrefix(String className, Collection<String> prefixes) {
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String renderStack(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement frame : stack) {
            sb.append("at ").append(frame).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
