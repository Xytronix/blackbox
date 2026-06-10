package sh.harold.blackbox.core.capture;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Captures per-pool memory usage from the standard MemoryPoolMXBeans. Entries are
 * {@code poolName -> "used committed max collectionUsed"} (space-separated longs, -1 where the
 * pool does not report a value; collectionUsed exists only for collected heap pools).
 */
public final class MemoryPools {

    private MemoryPools() {
    }

    /** Returns {@code base} with a "Memory pools" section appended; {@code base} on any failure. */
    public static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = collect();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Memory pools", entries));
        return out;
    }

    static Map<String, String> collect() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                MemoryUsage usage = pool.getUsage();
                MemoryUsage collection = pool.getCollectionUsage();
                long used = usage == null ? -1 : usage.getUsed();
                long committed = usage == null ? -1 : usage.getCommitted();
                long max = usage == null ? -1 : usage.getMax();
                long collectionUsed = collection == null ? -1 : collection.getUsed();
                out.put(pool.getName(), used + " " + committed + " " + max + " " + collectionUsed);
            }
        } catch (Exception e) {
            return Map.of();
        }
        return out;
    }
}
