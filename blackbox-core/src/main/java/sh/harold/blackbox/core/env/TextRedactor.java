package sh.harold.blackbox.core.env;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class TextRedactor {
    private static final System.Logger LOGGER = System.getLogger(TextRedactor.class.getName());

    public static final List<String> DEFAULT_PATTERNS = List.of(
        "(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)",
        "\\[(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z_.\\-]+)?\\](?::\\d{1,5})?",
        "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]{17,20}/[A-Za-z0-9._\\-]+"
    );

    private final List<Pattern> patterns;

    public TextRedactor(List<String> regexes) {
        Objects.requireNonNull(regexes, "regexes");
        List<Pattern> compiled = new ArrayList<>(regexes.size());
        for (String regex : regexes) {
            if (regex == null || regex.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                LOGGER.log(System.Logger.Level.WARNING,
                    "Skipping invalid redact pattern: " + regex, e);
            }
        }
        this.patterns = List.copyOf(compiled);
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
