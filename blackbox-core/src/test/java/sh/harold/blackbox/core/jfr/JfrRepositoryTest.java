package sh.harold.blackbox.core.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrRepositoryTest {

    @Name("test.Marker")
    static class Marker extends Event {
        @Label("n")
        int n;
    }

    @Test
    void repositoryBase_readsRepositoryFromFlightRecorderOptions() {
        assertEquals(Path.of("/data/jfr"), JfrRepository.repositoryBase(
            List.of("-Xmx1g", "-XX:FlightRecorderOptions=stackdepth=256,repository=/data/jfr"),
            Path.of("/tmp")));
    }

    @Test
    void repositoryBase_fallsBackToDefaultWhenAbsent() {
        assertEquals(Path.of("/tmp"), JfrRepository.repositoryBase(
            List.of("-Xmx1g", "-XX:+UseG1GC"), Path.of("/tmp")));
    }

    @Test
    void chunkFiles_returnsSortedJfrFilesOnly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("2.jfr"), "b");
        Files.writeString(dir.resolve("1.jfr"), "a");
        Files.writeString(dir.resolve("notes.txt"), "x");
        List<Path> chunks = JfrRepository.chunkFiles(dir);
        assertEquals(List.of("1.jfr", "2.jfr"),
            chunks.stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void merge_concatenatedChunksRemainReadable(@TempDir Path dir) throws Exception {
        Path a = recordMarkers(dir.resolve("a.jfr"), 3);
        Path b = recordMarkers(dir.resolve("b.jfr"), 4);

        Path merged = dir.resolve("merged.jfr");
        assertTrue(JfrRepository.merge(List.of(a, b), merged));

        int markers = 0;
        try (RecordingFile file = new RecordingFile(merged)) {
            while (file.hasMoreEvents()) {
                if (file.readEvent().getEventType().getName().equals("test.Marker")) {
                    markers++;
                }
            }
        }
        assertEquals(7, markers);
    }

    @Test
    void merge_emptyChunkListReturnsFalse(@TempDir Path dir) throws Exception {
        assertFalse(JfrRepository.merge(List.of(), dir.resolve("none.jfr")));
    }

    @Test
    void finalizeOrphan_clearsLockedChunkStateByte(@TempDir Path dir) throws Exception {
        Path recording = recordMarkers(dir.resolve("orphan.jfr"), 5);
        byte[] data = Files.readAllBytes(recording);
        data[64] = 0x13;
        Files.write(recording, data);
        assertThrows(Exception.class, () -> readMarkers(recording));

        assertTrue(JfrRepository.finalizeOrphan(recording));
        assertEquals(5, readMarkers(recording));
    }

    @Test
    void finalizeOrphan_dropsTruncatedTrailingChunk(@TempDir Path dir) throws Exception {
        Path a = recordMarkers(dir.resolve("a.jfr"), 3);
        Path b = recordMarkers(dir.resolve("b.jfr"), 4);
        Path merged = dir.resolve("merged.jfr");
        assertTrue(JfrRepository.merge(List.of(a, b), merged));

        byte[] partial = new byte[68];
        partial[0] = 'F';
        partial[1] = 'L';
        partial[2] = 'R';
        partial[3] = 0;
        try (var out = Files.newOutputStream(merged, StandardOpenOption.APPEND)) {
            out.write(partial);
        }

        assertTrue(JfrRepository.finalizeOrphan(merged));
        assertEquals(7, readMarkers(merged));
    }

    @Test
    void finalizeOrphan_returnsFalseWhenNothingSalvageable(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.jfr");
        Files.write(junk, new byte[]{1, 2, 3, 4});
        assertFalse(JfrRepository.finalizeOrphan(junk));
    }

    private static int readMarkers(Path recording) throws Exception {
        int markers = 0;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                if (file.readEvent().getEventType().getName().equals("test.Marker")) {
                    markers++;
                }
            }
        }
        return markers;
    }

    private static Path recordMarkers(Path target, int count) throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable("test.Marker");
            recording.start();
            for (int i = 0; i < count; i++) {
                Marker marker = new Marker();
                marker.n = i;
                marker.commit();
            }
            recording.stop();
            recording.dump(target);
        }
        return target;
    }
}
