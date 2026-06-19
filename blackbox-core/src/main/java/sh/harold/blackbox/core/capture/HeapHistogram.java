package sh.harold.blackbox.core.capture;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import sh.harold.blackbox.core.incident.DiagnosticSection;

public final class HeapHistogram {
    private static final int LIMIT = 30;
    private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");

    private HeapHistogram() {
    }

    public static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = collect();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Heap histogram", entries));
        return out;
    }

    public static List<String> topLines(int limit) {
        return formatLines(collect(), limit);
    }

    static List<String> formatLines(Map<String, String> entries, int limit) {
        List<String> out = new ArrayList<>(Math.min(limit, entries.size()));
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (out.size() >= limit) {
                break;
            }
            String[] parts = entry.getValue().split(" ");
            if (parts.length != 2) {
                continue;
            }
            try {
                out.add(entry.getKey() + "  instances=" + Long.parseLong(parts[0])
                    + "  bytes=" + humanBytes(Long.parseLong(parts[1])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static Map<String, String> collect() {
        try {
            String raw = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                new Object[] {null},
                new String[] {String[].class.getName()});
            return parse(raw);
        } catch (Throwable t) {
            return Map.of();
        }
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            Matcher m = HISTOGRAM_LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            out.putIfAbsent(m.group(3), m.group(1) + " " + m.group(2));
            if (out.size() >= LIMIT) {
                break;
            }
        }
        return out;
    }
}
