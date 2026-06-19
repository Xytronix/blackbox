package sh.harold.blackbox.core.incident;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record DiagnosticSection(String title, Map<String, String> entries, String preformatted) {
    public DiagnosticSection {
        Objects.requireNonNull(title, "title");
        entries = entries == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public DiagnosticSection(String title, Map<String, String> entries) {
        this(title, entries, null);
    }
}
