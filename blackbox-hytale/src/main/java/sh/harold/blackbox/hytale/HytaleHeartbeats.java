package sh.harold.blackbox.hytale;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import sh.harold.blackbox.core.incident.DiagnosticSection;
import sh.harold.blackbox.core.trigger.heartbeat.HeartbeatRegistry;

final class HytaleHeartbeats {
    private HytaleHeartbeats() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, HeartbeatRegistry registry, Clock clock) {
        if (registry == null) {
            return base;
        }
        Map<String, Instant> snapshot = registry.snapshot();
        if (snapshot.isEmpty()) {
            return base;
        }
        Instant now = clock == null ? null : clock.instant();
        Map<String, String> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Instant> e : snapshot.entrySet()) {
            Instant beat = e.getValue();
            String value = String.valueOf(beat);
            if (now != null && beat != null) {
                value = value + " (" + Duration.between(beat, now).toMillis() + " ms ago)";
            }
            entries.put(e.getKey(), value);
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Heartbeats", entries));
        return out;
    }
}
