package sh.harold.blackbox.core.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import sh.harold.blackbox.core.health.JfrTimeline;
import sh.harold.blackbox.core.json.JsonWriter;

final class ReportEvents {
    private static final int MAX_EVENTS = 120;

    private ReportEvents() {
    }

    private record ReportEvent(double frac, String type, String label, int count) {
        ReportEvent bump() {
            return new ReportEvent(frac, type, label, count + 1);
        }
    }

    static void write(JsonWriter json, List<ReportHtml.LogLine> log, JfrTimeline timeline) throws IOException {
        List<ReportEvent> all = buildEvents(log, timeline);
        int hidden = Math.max(0, all.size() - MAX_EVENTS);
        List<ReportEvent> shown = hidden > 0 ? all.subList(all.size() - MAX_EVENTS, all.size()) : all;
        writeEvents(json, shown);
        ReportJson.writeNullable(json, "eventsHidden", hidden > 0 ? (long) hidden : null);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, cut) + "…";
    }

    private static List<ReportEvent> buildEvents(List<ReportHtml.LogLine> log, JfrTimeline timeline) {
        if (timeline == null || timeline.start() == null || timeline.end() == null) {
            return List.of();
        }
        long start = timeline.start().toEpochMilli();
        long end = timeline.end().toEpochMilli();
        if (end <= start) {
            return List.of();
        }
        List<ReportEvent> raw = new ArrayList<>();
        List<JfrTimeline.ConnEvent> conns = timeline.connectionEvents();
        for (ReportHtml.LogLine line : log) {
            String type = switch (line.level()) {
                case "ERROR" -> "error";
                case "WARN" -> "warn";
                default -> conns.isEmpty() ? ReportHtml.connectionEventType(line) : null;
            };
            if (type == null || line.epochMs() < start || line.epochMs() > end) {
                continue;
            }
            String msg = line.message();
            int nl = msg.indexOf('\n');
            if (nl >= 0) {
                msg = msg.substring(0, nl);
            }
            msg = truncate(msg, 80);
            String label = line.source().isBlank() ? msg : line.source() + ": " + msg;
            raw.add(new ReportEvent((line.epochMs() - start) / (double) (end - start), type, label, 1));
        }
        for (long t : timeline.explicitGcTimes()) {
            if (t >= start && t <= end) {
                raw.add(new ReportEvent((t - start) / (double) (end - start), "gc", "System.gc()", 1));
            }
        }
        for (JfrTimeline.PluginEvent pe : timeline.pluginEvents()) {
            if (pe.timeMs() < start || pe.timeMs() > end) {
                continue;
            }
            String label = pe.category().isBlank() ? pe.message() : "[" + pe.category() + "] " + pe.message();
            label = truncate(label, 80);
            raw.add(new ReportEvent((pe.timeMs() - start) / (double) (end - start), "plugin", label, 1));
        }
        for (JfrTimeline.ConnEvent ce : conns) {
            if (ce.timeMs() < start || ce.timeMs() > end) {
                continue;
            }
            String label = ce.player() + " " + ce.phase()
                + (ce.detail() == null || ce.detail().isBlank() ? "" : " · " + ce.detail());
            label = truncate(label, 80);
            raw.add(new ReportEvent((ce.timeMs() - start) / (double) (end - start), "conn", label, 1));
        }
        raw.sort((a, b) -> Double.compare(a.frac(), b.frac()));

        List<ReportEvent> collapsed = new ArrayList<>();
        for (ReportEvent event : raw) {
            ReportEvent last = collapsed.isEmpty() ? null : collapsed.get(collapsed.size() - 1);
            if (last != null && last.type().equals(event.type()) && event.frac() - last.frac() < 0.005) {
                collapsed.set(collapsed.size() - 1, last.bump());
            } else {
                collapsed.add(event);
            }
        }
        return collapsed;
    }

    private static void writeEvents(JsonWriter json, List<ReportEvent> events) throws IOException {
        if (events.isEmpty()) {
            json.name("events").nullValue();
            return;
        }
        json.name("events").beginArray();
        for (ReportEvent event : events) {
            json.beginArray();
            json.value(Math.round(event.frac() * 10000) / 10000.0);
            json.value(event.type());
            json.value(event.count() > 1 ? event.label() + " ×" + event.count() : event.label());
            json.endArray();
        }
        json.endArray();
    }
}
