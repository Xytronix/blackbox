package sh.harold.blackbox.hytale;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytaleServerLog {

    private HytaleServerLog() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, boolean includeServerLog,
                                            int tailLines, PlayerNameMasker nameMasker, boolean sanitize) {
        if (!includeServerLog || tailLines <= 0) {
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
        out.add(new DiagnosticSection("Server log", Map.of(), sanitize ? nameMasker.maskText(tail) : tail));
        return out;
    }
}
