package sh.harold.blackbox.core.capture;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;

public final class MemoryPools {

    private MemoryPools() {
    }

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
