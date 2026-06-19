package sh.harold.blackbox.hytale;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import sh.harold.blackbox.core.incident.DiagnosticSection;

final class HytaleModConfigs {
    private static final Path MODS_DIR = Paths.get("mods");
    private static final int MAX_BYTES = 96 * 1024;
    private static final int MAX_FILES = 12;

    private HytaleModConfigs() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, boolean includeModConfigs,
                                            List<Path> registered) {
        List<DiagnosticSection> found = sections(includeModConfigs, registered);
        if (found.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.addAll(found);
        return out;
    }

    private static List<DiagnosticSection> sections(boolean includeModConfigs, List<Path> registered) {
        List<DiagnosticSection> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Path file : registered) {
            if (out.size() >= MAX_FILES) {
                break;
            }
            Path key = normalize(file);
            if (key == null || !seen.add(key) || !Files.isRegularFile(file)) {
                continue;
            }
            String content = read(file);
            if (content != null) {
                out.add(new DiagnosticSection(title(file), Map.of(), content));
            }
        }
        if (includeModConfigs) {
            scrape(out, seen);
        }
        return out;
    }

    private static void scrape(List<DiagnosticSection> out, Set<Path> seen) {
        if (!Files.isDirectory(MODS_DIR)) {
            return;
        }
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
                            if (out.size() >= MAX_FILES || !seen.add(normalize(p))) {
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
    }

    private static Path normalize(Path file) {
        try {
            return file.toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String title(Path file) {
        try {
            Path cwd = Paths.get("").toAbsolutePath();
            Path abs = file.toAbsolutePath().normalize();
            if (abs.startsWith(cwd)) {
                return cwd.relativize(abs).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        return file.getFileName().toString();
    }

    private static boolean isConfigFile(String name, String modName) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("config.json") || (!modName.isEmpty() && lower.equals(modName + ".json"));
    }

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
