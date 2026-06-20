package sh.harold.blackbox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.harold.blackbox.core.bundle.BundleAttachment;

class HytaleBundleExtrasProviderTest {

    @Test
    void classPresenceCheckDoesNotInitializeClass() {
        InitMarker.INITIALIZED.set(false);
        String className = InitProbe.class.getName();

        boolean present = HytaleBundleExtrasProvider.isClassPresent(
            className,
            HytaleBundleExtrasProviderTest.class.getClassLoader()
        );

        assertTrue(present);
        assertFalse(InitMarker.INITIALIZED.get(), "Class initialization should not run.");
    }

    @Test
    void classPresenceCheckReturnsFalseForMissingClass() {
        boolean present = HytaleBundleExtrasProvider.isClassPresent(
            "not.present.ClassName",
            HytaleBundleExtrasProviderTest.class.getClassLoader()
        );
        assertFalse(present);
    }

    @Test
    void classPresenceCheckGuardsThrowableFromLoader() {
        ClassLoader throwingLoader = new ThrowingLoader(HytaleBundleExtrasProviderTest.class.getClassLoader());
        boolean present = HytaleBundleExtrasProvider.isClassPresent("boom.Broken", throwingLoader);
        assertFalse(present);
    }

    @Test
    void readTailRespectsByteBoundForLargeSingleLine(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("latest.log");
        String prefix = "x".repeat(20_000);
        String suffix = "::THE_END::";
        Files.writeString(log, prefix + suffix, StandardCharsets.UTF_8);

        String tail = HytaleBundleExtrasProvider.readTail(log, 1, 64);

        assertTrue(tail.endsWith(suffix));
        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= 64);
    }

    @Test
    void readTailKeepsLineBehaviorWhenWithinByteBound(@TempDir Path tempDir) throws Exception {
        Path log = tempDir.resolve("lines.log");
        Files.writeString(log, "a\nb\nc\n", StandardCharsets.UTF_8);

        String tail = HytaleBundleExtrasProvider.readTail(log, 2, 1024);

        assertEquals("b\nc\n", tail);
    }

    @Test
    void emitsNumberedThreadDumpsWhenMultipleCaptured() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of("dump-A", "dump-B", "dump-C"));

        assertEquals("dump-A", contentOf(extras, "extras/threads-1.txt"));
        assertEquals("dump-B", contentOf(extras, "extras/threads-2.txt"));
        assertEquals("dump-C", contentOf(extras, "extras/threads-3.txt"));
        assertFalse(hasPath(extras, "extras/threads.txt"));
    }

    @Test
    void usesSingleCapturedDumpAsThreadsTxt() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of("only-dump"));

        assertEquals("only-dump", contentOf(extras, "extras/threads.txt"));
    }

    @Test
    void capturesLiveThreadDumpWhenNoneProvided() {
        List<BundleAttachment> extras =
            HytaleBundleExtrasProvider.threadDumpAttachments(List.of());

        assertTrue(contentOf(extras, "extras/threads.txt").startsWith("threads="));
    }

    private static String contentOf(List<BundleAttachment> extras, String path) {
        return extras.stream()
            .filter(a -> a.pathInZip().equals(path))
            .map(a -> new String(a.data(), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    }

    private static boolean hasPath(List<BundleAttachment> extras, String path) {
        return extras.stream().anyMatch(a -> a.pathInZip().equals(path));
    }

    private static final class InitMarker {
        private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    }

    private static final class InitProbe {
        static {
            InitMarker.INITIALIZED.set(true);
        }
    }

    private static final class ThrowingLoader extends ClassLoader {
        private ThrowingLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("boom.Broken".equals(name)) {
                throw new LinkageError("simulated linkage failure");
            }
            return super.loadClass(name, resolve);
        }
    }
}
