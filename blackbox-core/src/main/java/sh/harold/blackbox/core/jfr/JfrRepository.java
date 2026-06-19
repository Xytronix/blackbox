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

    private static final byte[] CHUNK_MAGIC = {'F', 'L', 'R', 0};
    private static final int CHUNK_HEADER_SIZE = 68;
    private static final int CHUNK_SIZE_OFFSET = 8;
    private static final int METADATA_OFFSET = 24;
    private static final int FILE_STATE_OFFSET = 64;

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

    public static boolean finalizeOrphan(Path recording) throws IOException {
        if (recording == null || !Files.isRegularFile(recording)) {
            return false;
        }
        byte[] data = Files.readAllBytes(recording);
        int length = data.length;
        int offset = 0;
        int completeEnd = 0;
        boolean modified = false;
        while (offset + CHUNK_HEADER_SIZE <= length) {
            if (!hasChunkMagic(data, offset)) {
                break;
            }
            long chunkSize = readLongBE(data, offset + CHUNK_SIZE_OFFSET);
            if (chunkSize <= 0 || offset + chunkSize > length) {
                break;
            }
            long metadataOffset = readLongBE(data, offset + METADATA_OFFSET);
            if (metadataOffset <= 0 || metadataOffset >= chunkSize) {
                break;
            }
            if (data[offset + FILE_STATE_OFFSET] != 0) {
                data[offset + FILE_STATE_OFFSET] = 0;
                modified = true;
            }
            offset += (int) chunkSize;
            completeEnd = offset;
        }
        if (completeEnd == 0) {
            return false;
        }
        if (completeEnd < length) {
            data = java.util.Arrays.copyOf(data, completeEnd);
            modified = true;
        }
        if (modified) {
            Files.write(recording, data);
        }
        return true;
    }

    private static boolean hasChunkMagic(byte[] data, int offset) {
        for (int i = 0; i < CHUNK_MAGIC.length; i++) {
            if (data[offset + i] != CHUNK_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static long readLongBE(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }
}
