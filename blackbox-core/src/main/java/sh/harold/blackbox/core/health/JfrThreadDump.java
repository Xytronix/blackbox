package sh.harold.blackbox.core.health;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import sh.harold.blackbox.core.incident.DiagnosticSection;

public final class JfrThreadDump {

    private JfrThreadDump() {
    }

    public static String lastDump(Path recording) {
        if (recording == null || !Files.isRegularFile(recording)) {
            return null;
        }
        String last = null;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if ("jdk.ThreadDump".equals(event.getEventType().getName())) {
                    String result = event.getString("result");
                    if (result != null && !result.isBlank()) {
                        last = result;
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return last;
    }

    public static DiagnosticSection stuckSection(String dump, Collection<String> frameworkPrefixes) {
        if (dump == null || dump.isBlank()) {
            return null;
        }
        List<String> best = null;
        int bestScore = -1;
        for (List<String> block : blocks(dump)) {
            if (!isStuck(block)) {
                continue;
            }
            int score = appFrameCount(block) * 2 + (isMutexWait(block) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = block;
            }
        }
        return best == null ? null : section(best, blockedIn(best, frameworkPrefixes));
    }

    private static int appFrameCount(List<String> block) {
        int count = 0;
        for (String line : block) {
            String text = line.strip();
            if (text.startsWith("at ")) {
                String cls = frameClass(text);
                if (cls != null && !isJdk(cls)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isMutexWait(List<String> block) {
        String lock = lockOf(block);
        return lock != null && !lock.contains("ConditionObject");
    }

    private static List<List<String>> blocks(String dump) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : dump.split("\n")) {
            if (line.startsWith("\"")) {
                current = new ArrayList<>();
                blocks.add(current);
                current.add(line);
            } else if (current != null) {
                current.add(line);
            }
        }
        return blocks;
    }

    private static boolean isStuck(List<String> block) {
        String state = stateOf(block);
        if (state == null || !(state.startsWith("WAITING") || state.startsWith("BLOCKED"))) {
            return false;
        }
        boolean lockWait = false;
        boolean appFrame = false;
        for (String line : block) {
            String text = line.strip();
            if (text.startsWith("- parking to wait for") || text.startsWith("- waiting to lock")) {
                lockWait = true;
            } else if (text.startsWith("at ")) {
                String cls = frameClass(text);
                if (cls != null && !isJdk(cls)) {
                    appFrame = true;
                }
            }
        }
        return lockWait && appFrame;
    }

    private static String blockedIn(List<String> block, Collection<String> frameworkPrefixes) {
        String lockSite = null;
        for (String line : block) {
            String text = line.strip();
            if (!text.startsWith("at ")) {
                continue;
            }
            String cls = frameClass(text);
            if (cls == null || isJdk(cls)) {
                continue;
            }
            String classMethod = frameClassMethod(text);
            if (lockSite == null) {
                lockSite = classMethod;
            }
            if (!matchesPrefix(cls, frameworkPrefixes)) {
                return classMethod;
            }
        }
        return lockSite;
    }

    private static DiagnosticSection section(List<String> block, String blockedIn) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Thread", threadName(block.get(0)));
        String state = stateOf(block);
        if (state != null) {
            entries.put("State", state);
        }
        String lock = lockOf(block);
        if (lock != null) {
            entries.put("Waiting on", lock);
        }
        if (blockedIn != null) {
            entries.put("Blocked in", blockedIn);
        }
        StringBuilder stack = new StringBuilder();
        for (String line : block) {
            String text = line.strip();
            if (text.startsWith("at ") || text.startsWith("- ")) {
                stack.append(text).append('\n');
            }
        }
        return new DiagnosticSection("Stalled thread", entries, stack.toString().stripTrailing());
    }

    private static String stateOf(List<String> block) {
        for (String line : block) {
            int i = line.indexOf("java.lang.Thread.State:");
            if (i >= 0) {
                return line.substring(i + "java.lang.Thread.State:".length()).strip();
            }
        }
        return null;
    }

    private static String lockOf(List<String> block) {
        for (String line : block) {
            String text = line.strip();
            if (text.startsWith("- parking to wait for") || text.startsWith("- waiting to lock")) {
                int a = text.indexOf("(a ");
                int b = text.lastIndexOf(')');
                if (a >= 0 && b > a) {
                    return text.substring(a + 3, b).strip();
                }
            }
        }
        return null;
    }

    private static String threadName(String header) {
        int a = header.indexOf('"');
        int b = header.indexOf('"', a + 1);
        return (a >= 0 && b > a) ? header.substring(a + 1, b) : header.strip();
    }

    private static String frameClassMethod(String atLine) {
        String s = atLine.strip().substring(3).strip();
        int paren = s.indexOf('(');
        return paren >= 0 ? s.substring(0, paren) : s;
    }

    private static String frameClass(String atLine) {
        String classMethod = frameClassMethod(atLine);
        int lastDot = classMethod.lastIndexOf('.');
        return lastDot > 0 ? classMethod.substring(0, lastDot) : classMethod;
    }

    private static boolean isJdk(String cls) {
        return cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")
            || cls.startsWith("sh.harold.blackbox.");
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
}
