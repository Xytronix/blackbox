package sh.harold.blackbox.core.jfr;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class JfrRepository {

    private static final String FLIGHT_RECORDER_OPTIONS = "FlightRecorderOptions=";

    private JfrRepository() {
    }

    public static Path repositoryBase(List<String> jvmArgs, Path defaultBase) {
        if (jvmArgs != null) {
            for (String arg : jvmArgs) {
                if (arg == null) {
                    continue;
                }
                int start = arg.indexOf(FLIGHT_RECORDER_OPTIONS);
                if (start < 0) {
                    continue;
                }
                String options = arg.substring(start + FLIGHT_RECORDER_OPTIONS.length());
                for (String pair : options.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0, eq).trim().equals("repository")) {
                        String path = pair.substring(eq + 1).trim();
                        if (!path.isEmpty()) {
                            return Path.of(path);
                        }
                    }
                }
            }
        }
        return defaultBase;
    }

    public static Optional<Path> sessionDirForPid(Path base, long pid) throws IOException {
        if (base == null || !Files.isDirectory(base)) {
            return Optional.empty();
        }
        String suffix = "_" + pid;
        try (Stream<Path> entries = Files.list(base)) {
            return entries
                .filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString().endsWith(suffix))
                .max(Comparator.comparing(dir -> dir.getFileName().toString()));
        }
    }

    public static List<Path> chunkFiles(Path sessionDir) throws IOException {
        if (sessionDir == null || !Files.isDirectory(sessionDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(sessionDir)) {
            List<Path> chunks = new ArrayList<>(entries
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".jfr"))
                .toList());
            chunks.sort(Comparator.comparing(file -> file.getFileName().toString()));
            return chunks;
        }
    }

    public static boolean merge(List<Path> chunks, Path output) throws IOException {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(output)) {
            for (Path chunk : chunks) {
                Files.copy(chunk, out);
            }
        }
        return true;
    }
}
