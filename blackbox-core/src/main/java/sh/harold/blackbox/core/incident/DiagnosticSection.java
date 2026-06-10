package sh.harold.blackbox.core.incident;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A named section contributed by a plugin (e.g. Refixes config, Hyinit mixins), rendered as its
 * own card in the incident report. Either a key/value table ({@code entries}) or, when
 * {@code preformatted} is non-null, a verbatim collapsible block (e.g. a raw config file).
 */
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
