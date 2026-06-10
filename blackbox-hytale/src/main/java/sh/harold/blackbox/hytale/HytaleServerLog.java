package sh.harold.blackbox.hytale;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Surfaces the tail of the latest server log as a preformatted "Server log" diagnostics section,
 * so the report can render it as a parsed, filterable log view. The pipeline redacts diagnostic
 * values, so any IP address in a log line is masked before it reaches the report.
 */
final class HytaleServerLog {

    private HytaleServerLog() {
    }

    /** Returns {@code base} with a preformatted "Server log" section appended when a tail exists. */
    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, int tailLines) {
        if (tailLines <= 0) {
            return base;
        }
        Path log = HytaleBundleExtrasProvider.latestServerLog();
        if (log == null) {
            return base;
        }
        String tail;
        try {
            tail = HytaleBundleExtrasProvider.readTail(log, tailLines);
        } catch (IOException e) {
            return base;
        }
        if (tail.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Server log", Map.of(), tail));
        return out;
    }
}
