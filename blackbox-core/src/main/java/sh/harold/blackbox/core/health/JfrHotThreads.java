package sh.harold.blackbox.core.health;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public final class JfrHotThreads {
    private static final String EXECUTION_SAMPLE = "jdk.ExecutionSample";

    private JfrHotThreads() {
    }

    public static List<HealthSnapshot.HotThread> parse(Path recording, int limit) {
        Map<String, long[]> samplesByThread = new HashMap<>();
        Map<String, Map<String, Long>> topMethodByThread = new HashMap<>();
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!EXECUTION_SAMPLE.equals(event.getEventType().getName())) {
                    continue;
                }
                RecordedThread thread = event.getThread("sampledThread");
                if (thread == null) {
                    thread = event.getThread();
                }
                String threadName = thread == null ? null : thread.getJavaName();
                if (threadName == null || threadName.isBlank()) {
                    threadName = thread == null ? null : thread.getOSName();
                }
                if (threadName == null || threadName.isBlank()) {
                    threadName = "<unknown>";
                }
                samplesByThread.computeIfAbsent(threadName, k -> new long[1])[0]++;

                String top = topMethod(event.getStackTrace());
                if (top != null) {
                    topMethodByThread
                        .computeIfAbsent(threadName, k -> new HashMap<>())
                        .merge(top, 1L, Long::sum);
                }
            }
        } catch (Exception e) {
            return List.of();
        }

        List<HealthSnapshot.HotThread> out = new ArrayList<>(samplesByThread.size());
        for (Map.Entry<String, long[]> entry : samplesByThread.entrySet()) {
            String name = entry.getKey();
            long samples = entry.getValue()[0];
            out.add(new HealthSnapshot.HotThread(name, samples, dominantMethod(topMethodByThread.get(name))));
        }
        out.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static final String[] ENGINE_PREFIXES = {
        "com.hypixel.", "java.", "jdk.", "sun.", "javax.", "io.netty.", "com.mojang.",
        "it.unimi.", "org.joml", "org.lwjgl", "com.google.", "kotlin.", "scala.",
        "org.spongepowered.", "org.bson", "org.mongodb", "org.slf4j", "org.apache."
    };

    public static LinkedHashMap<String, String> modContribution(Path recording, int limit) {
        Map<String, long[]> samplesByMod = new HashMap<>();
        Map<String, Map<String, Long>> methodByMod = new HashMap<>();
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!EXECUTION_SAMPLE.equals(event.getEventType().getName())) {
                    continue;
                }
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null) {
                    continue;
                }
                Set<String> modsInSample = new HashSet<>();
                for (RecordedFrame frame : stack.getFrames()) {
                    if (!frame.isJavaFrame() || frame.getMethod() == null) {
                        continue;
                    }
                    String type = frame.getMethod().getType().getName();
                    if (isEngine(type)) {
                        continue;
                    }
                    String mod = modId(type);
                    modsInSample.add(mod);
                    methodByMod.computeIfAbsent(mod, k -> new HashMap<>())
                        .merge(type + "." + frame.getMethod().getName(), 1L, Long::sum);
                }
                for (String mod : modsInSample) {
                    samplesByMod.computeIfAbsent(mod, k -> new long[1])[0]++;
                }
            }
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }

        List<Map.Entry<String, long[]>> ordered = new ArrayList<>(samplesByMod.entrySet());
        ordered.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, long[]> entry : ordered) {
            if (count++ >= limit) {
                break;
            }
            long samples = entry.getValue()[0];
            out.put(entry.getKey(), samples + " samples · " + dominantMethod(methodByMod.get(entry.getKey())));
        }
        return out;
    }

    static boolean isEngine(String type) {
        for (String prefix : ENGINE_PREFIXES) {
            if (type.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static String modId(String type) {
        int first = type.indexOf('.');
        if (first < 0) {
            return type;
        }
        int second = type.indexOf('.', first + 1);
        if (second < 0) {
            return type;
        }
        int third = type.indexOf('.', second + 1);
        return third < 0 ? type : type.substring(0, third);
    }

    private static String topMethod(RecordedStackTrace stack) {
        if (stack == null) {
            return null;
        }
        List<RecordedFrame> frames = stack.getFrames();
        for (RecordedFrame frame : frames) {
            if (frame.isJavaFrame() && frame.getMethod() != null) {
                return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
            }
        }
        return null;
    }

    private static String dominantMethod(Map<String, Long> methods) {
        if (methods == null || methods.isEmpty()) {
            return "<unknown>";
        }
        return methods.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("<unknown>");
    }
}
