package sh.harold.blackbox.hytale;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytaleServerSettings {
    private static final Path CONFIG_FILE = Paths.get("config.json");
    private static final int MAX_BYTES = 96 * 1024;
    private static final Pattern PASSWORD_VALUE =
        Pattern.compile("(\"Password\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)+\"");

    private HytaleServerSettings() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, boolean includeServerConfig) {
        if (!includeServerConfig) {
            return base;
        }
        String content = read();
        if (content == null) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("config.json", Map.of(), redact(content)));
        return out;
    }

    private static String read() {
        try {
            if (!Files.isRegularFile(CONFIG_FILE)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(CONFIG_FILE);
            if (bytes.length == 0) {
                return null;
            }
            if (bytes.length <= MAX_BYTES) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8)
                + "\n... [truncated " + (bytes.length - MAX_BYTES) + " more bytes]";
        } catch (Exception e) {
            return null;
        }
    }

    static String redact(String json) {
        String out = PASSWORD_VALUE.matcher(json).replaceAll("$1\"<redacted>\"");
        return redactObject(out, "AuthCredentialStore");
    }

    private static String redactObject(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) {
            return json;
        }
        int start = m.end() - 1;
        boolean inString = false;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(0, start) + "\"<redacted>\"" + json.substring(i + 1);
                }
            }
        }
        return json;
    }
}
