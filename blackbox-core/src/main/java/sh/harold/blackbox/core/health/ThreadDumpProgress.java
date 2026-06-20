package sh.harold.blackbox.core.health;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.harold.blackbox.core.incident.DiagnosticSection;

public final class ThreadDumpProgress {
    private static final int MAX_LISTED = 20;

    private ThreadDumpProgress() {
    }

    public static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, List<String> dumps) {
        DiagnosticSection section = analyze(dumps);
        if (section == null) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(section);
        return out;
    }

    static DiagnosticSection analyze(List<String> dumps) {
        if (dumps == null || dumps.size() < 2) {
            return null;
        }
        List<Map<String, String[]>> parsed = new ArrayList<>(dumps.size());
        for (String dump : dumps) {
            parsed.add(parse(dump));
        }

        Map<String, String> stuck = new LinkedHashMap<>();
        int stuckCount = 0;
        for (Map.Entry<String, String[]> entry : parsed.get(0).entrySet()) {
            String name = entry.getKey();
            String state = entry.getValue()[0];
            String frame = entry.getValue()[1];
            if (!isStuckCandidate(state, frame)) {
                continue;
            }
            boolean unchanged = true;
            for (int i = 1; i < parsed.size(); i++) {
                String[] info = parsed.get(i).get(name);
                if (info == null || !state.equals(info[0]) || !frame.equals(info[1])) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) {
                stuckCount++;
                if (stuck.size() < MAX_LISTED) {
                    stuck.put(name, state + " @ " + frame);
                }
            }
        }

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Dumps compared", Integer.toString(dumps.size()));
        if (stuckCount == 0) {
            entries.put("Verdict", "All threads advanced across the dumps; no hard hang detected.");
            return new DiagnosticSection("Thread progress", entries);
        }
        entries.put("Verdict", stuckCount + " thread(s) showed no progress (same state and top frame) across all "
            + dumps.size() + " dumps — likely hung or in a tight loop.");
        entries.putAll(stuck);
        if (stuckCount > stuck.size()) {
            entries.put("(and more)", (stuckCount - stuck.size()) + " additional thread(s) not listed");
        }
        return new DiagnosticSection("Thread progress", entries);
    }

    private static boolean isStuckCandidate(String state, String frame) {
        if (!"RUNNABLE".equals(state) && !"BLOCKED".equals(state)) {
            return false;
        }
        return !isIdleFrame(frame);
    }

    private static boolean isIdleFrame(String frame) {
        return frame.contains("epollWait") || frame.contains("EPoll")
            || frame.contains("kevent") || frame.contains("KQueue")
            || frame.contains("accept0") || frame.contains("socketAccept")
            || frame.contains("socketRead") || frame.contains("Net.poll")
            || frame.contains("Poll.poll") || frame.contains("park");
    }

    private static Map<String, String[]> parse(String dump) {
        Map<String, String[]> threads = new LinkedHashMap<>();
        if (dump == null) {
            return threads;
        }
        String name = null;
        String state = null;
        for (String line : dump.split("\n")) {
            if (line.startsWith("\"")) {
                int end = line.indexOf('"', 1);
                name = end > 1 ? line.substring(1, end) : null;
                state = stateOf(line);
            } else if (name != null && line.startsWith("    at ")) {
                threads.putIfAbsent(name, new String[] {state == null ? "" : state, line.trim()});
                name = null;
            }
        }
        return threads;
    }

    private static String stateOf(String header) {
        int i = header.indexOf("state=");
        if (i < 0) {
            return "";
        }
        int start = i + "state=".length();
        int end = start;
        while (end < header.length() && !Character.isWhitespace(header.charAt(end))) {
            end++;
        }
        return header.substring(start, end);
    }
}
