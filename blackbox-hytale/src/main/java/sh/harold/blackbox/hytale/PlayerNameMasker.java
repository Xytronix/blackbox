package sh.harold.blackbox.hytale;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PlayerNameMasker {
    private static final int MAX_TRACKED = 4096;

    private static final String NAME_RE = "\\w[\\w-]{1,31}";
    private static final String UUID_RE =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Pattern LOG_DISCONNECT_LINE = Pattern.compile("\\bDisconnecting (" + NAME_RE + ") at ");
    private static final Pattern LOG_PLAYER_LINE =
        Pattern.compile("\\bPlayer '(" + NAME_RE + ")' \\((" + UUID_RE + ")\\)");
    private static final Pattern LOG_LOGIN_NAME_UUID =
        Pattern.compile(", (" + NAME_RE + "), (" + UUID_RE + ")\\}");
    private static final Pattern LOG_LOGIN_UUID_NAME =
        Pattern.compile(", (" + UUID_RE + "), (" + NAME_RE + ")\\}");

    private record Masked(String token, Pattern pattern, String raw) {
    }

    private final ConcurrentHashMap<String, Masked> masked = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        Masked entry = entryFor(name.trim());
        return entry == null ? "player-?" : entry.token();
    }

    void remember(String name, UUID uuid) {
        if (name == null || name.isBlank()) {
            return;
        }
        learnPair(name.trim(), uuid == null ? null : uuid.toString());
    }

    private void learnPair(String name, String uuid) {
        Masked entry = entryFor(name);
        if (entry != null && uuid != null) {
            masked.putIfAbsent(uuid, new Masked(entry.token(), literal(uuid), uuid));
        }
    }

    String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        learnFromLog(text);
        if (masked.isEmpty()) {
            return text;
        }
        for (Masked entry : masked.values()) {
            if (text.indexOf(entry.raw()) < 0) {
                continue;
            }
            text = entry.pattern().matcher(text).replaceAll(entry.token());
        }
        return text;
    }

    private void learnFromLog(String text) {
        Matcher m = LOG_DISCONNECT_LINE.matcher(text);
        while (m.find()) {
            entryFor(m.group(1));
        }
        m = LOG_PLAYER_LINE.matcher(text);
        while (m.find()) {
            learnPair(m.group(1), m.group(2));
        }
        m = LOG_LOGIN_NAME_UUID.matcher(text);
        while (m.find()) {
            learnPair(m.group(1), m.group(2));
        }
        m = LOG_LOGIN_UUID_NAME.matcher(text);
        while (m.find()) {
            learnPair(m.group(2), m.group(1));
        }
    }

    private Masked entryFor(String key) {
        Masked existing = masked.get(key);
        if (existing != null) {
            return existing;
        }
        if (masked.size() >= MAX_TRACKED) {
            return null;
        }
        return masked.computeIfAbsent(key,
            k -> new Masked("player-" + counter.incrementAndGet(), literal(k), k));
    }

    private static Pattern literal(String value) {
        return Pattern.compile("\\b" + Pattern.quote(value) + "\\b");
    }
}
