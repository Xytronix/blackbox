package sh.harold.blackbox.hytale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Surfaces the actual on-disk mod config files (e.g. mods/IroriPowered_Refixes/Refixes.json) as
 * verbatim collapsible blocks in the report — the real file the admin edits, not a reconstruction.
 * Any mod directory qualifies; only {@code config.json} or the mod-named {@code <Name>.json}
 * counts as a config (never data files), capped at {@value #MAX_FILES} files total. The pipeline
 * redacts the bodies.
 */
final class HytaleModConfigs {
    private static final Path MODS_DIR = Paths.get("mods");
    private static final int MAX_BYTES = 96 * 1024;
    private static final int MAX_FILES = 12;

    private HytaleModConfigs() {
    }

    /** Returns {@code base} with a verbatim section per surfaced mod config file appended. */
    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        List<DiagnosticSection> found = sections();
        if (found.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.addAll(found);
        return out;
    }

    private static List<DiagnosticSection> sections() {
        if (!Files.isDirectory(MODS_DIR)) {
            return List.of();
        }
        List<DiagnosticSection> out = new ArrayList<>();
        try (Stream<Path> modDirs = Files.list(MODS_DIR)) {
            List<Path> dirs = modDirs
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(dir -> dir.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
            for (Path dir : dirs) {
                if (out.size() >= MAX_FILES) {
                    break;
                }
                String modName = modName(dir.getFileName().toString());
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(p -> isConfigFile(p.getFileName().toString(), modName))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(p -> {
                            if (out.size() >= MAX_FILES) {
                                return;
                            }
                            String content = read(p);
                            if (content != null) {
                                String title = "mods/" + dir.getFileName() + "/" + p.getFileName();
                                out.add(new DiagnosticSection(title, Map.of(), content));
                            }
                        });
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** A real config file (config.json or the mod-named <Name>.json) — not data files. */
    private static boolean isConfigFile(String name, String modName) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("config.json") || (!modName.isEmpty() && lower.equals(modName + ".json"));
    }

    /** Mod name from a "Group_Name" directory, lowercased: "IroriPowered_Refixes" → "refixes". */
    private static String modName(String dirName) {
        String lower = dirName.toLowerCase(Locale.ROOT);
        int us = lower.lastIndexOf('_');
        return us < 0 ? lower : lower.substring(us + 1);
    }

    private static String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return null;
            }
            if (bytes.length <= MAX_BYTES) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8)
                + "\n... [truncated " + (bytes.length - MAX_BYTES) + " more bytes]";
        } catch (IOException e) {
            return null;
        }
    }
}
