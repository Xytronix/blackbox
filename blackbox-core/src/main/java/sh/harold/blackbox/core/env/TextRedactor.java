package sh.harold.blackbox.core.env;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TextRedactor {
    public static final List<String> DEFAULT_PATTERNS = List.of(
        "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)",
        "(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])",
        "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z_.\\-]+)?\\](?::\\d{1,5})?",
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private final List<Pattern> patterns;

    public TextRedactor(List<String> regexes) {
        Objects.requireNonNull(regexes, "regexes");
        this.patterns = regexes.stream()
            .map(Pattern::compile)
            .toList();
    }

    public String redact(String text) {
        if (text == null || text.isEmpty() || patterns.isEmpty()) {
            return text;
        }
        for (Pattern p : patterns) {
            text = p.matcher(text).replaceAll("[REDACTED]");
        }
        return text;
    }

    public byte[] redact(byte[] data) {
        if (data == null || data.length == 0 || patterns.isEmpty()) {
            return data;
        }
        String text = new String(data, StandardCharsets.UTF_8);
        String redacted = redact(text);
        if (text.equals(redacted)) {
            return data;
        }
        return redacted.getBytes(StandardCharsets.UTF_8);
    }

    public boolean hasPatterns() {
        return !patterns.isEmpty();
    }
}
