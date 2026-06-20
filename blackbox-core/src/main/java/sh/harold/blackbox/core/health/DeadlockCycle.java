package sh.harold.blackbox.core.health;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.trigger.TriggerEvent;
import sh.harold.blackbox.core.trigger.TriggerKind;

public final class DeadlockCycle {
    private DeadlockCycle() {
    }

    public static List<DiagnosticSection> prependTo(List<DiagnosticSection> base, TriggerEvent event) {
        DiagnosticSection section = build(event);
        if (section == null) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base.size() + 1);
        out.add(section);
        out.addAll(base);
        return out;
    }

    static DiagnosticSection build(TriggerEvent event) {
        if (event == null || event.kind() != TriggerKind.DEADLOCK) {
            return null;
        }
        long[] ids = threadIds(event);
        if (ids.length == 0) {
            return null;
        }
        ThreadInfo[] infos;
        try {
            infos = ManagementFactory.getThreadMXBean().getThreadInfo(ids, true, true);
        } catch (Exception e) {
            return null;
        }
        Map<String, String> entries = new LinkedHashMap<>();
        StringBuilder chain = new StringBuilder();
        for (ThreadInfo info : infos) {
            if (info == null) {
                continue;
            }
            String who = "\"" + info.getThreadName() + "\" (#" + info.getThreadId() + ")";
            LockInfo lock = info.getLockInfo();
            String owner = info.getLockOwnerName() == null
                ? null
                : "\"" + info.getLockOwnerName() + "\" (#" + info.getLockOwnerId() + ")";
            if (lock != null && owner != null) {
                entries.put(who, "waiting on " + lock + " held by " + owner);
            } else if (lock != null) {
                entries.put(who, "waiting on " + lock);
            } else {
                entries.put(who, info.getThreadState().toString());
            }
            chain.append(who).append(' ').append(info.getThreadState()).append('\n');
            if (lock != null) {
                chain.append("    waiting on ").append(lock).append('\n');
            }
            if (owner != null) {
                chain.append("    held by ").append(owner).append('\n');
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        return new DiagnosticSection("Deadlock cycle", entries, chain.toString().stripTrailing());
    }

    private static long[] threadIds(TriggerEvent event) {
        String raw = event.attrs().get("threadIds");
        if (raw == null) {
            raw = event.attrs().get("threadId");
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
            }
        }
        return Arrays.copyOf(ids, count);
    }
}
