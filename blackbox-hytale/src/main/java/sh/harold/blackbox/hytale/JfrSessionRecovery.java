package sh.harold.blackbox.hytale;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import sh.harold.blackbox.core.capture.CapturePipeline;
import sh.harold.blackbox.core.jfr.JfrRepository;

final class JfrSessionRecovery {
    private final Clock clock;
    private final System.Logger logger;
    private final Path rollingFile;
    private final Path recoverDir;
    private final Path sessionPointer;
    private volatile Path currentSessionDir;

    JfrSessionRecovery(Clock clock, System.Logger logger, Path rollingFile, Path recoverDir, Path sessionPointer) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.rollingFile = Objects.requireNonNull(rollingFile, "rollingFile");
        this.recoverDir = Objects.requireNonNull(recoverDir, "recoverDir");
        this.sessionPointer = Objects.requireNonNull(sessionPointer, "sessionPointer");
    }

    void recoverOrphanedRecordings(boolean snapshotsEnabled, Supplier<CapturePipeline> pipeline,
                                   ExecutorService worker) {
        String previousSession = readSessionPointer();
        locateCurrentSession();
        persistCurrentSession();
        worker.execute(() -> {
            try {
                Files.createDirectories(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to create the recovery directory.", e);
            }
            boolean harvested = harvestPreviousRepository(previousSession, pipeline);
            if (!harvested && snapshotsEnabled) {
                try {
                    if (Files.exists(rollingFile)) {
                        Path staged = recoverDir.resolve("rolling-" + clock.millis() + ".jfr");
                        Files.move(rollingFile, staged, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING, "Failed to stage an orphaned recording for recovery.", e);
                }
            }
            try {
                pipeline.get().recoverOrphans(recoverDir);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Recovery scan failed.", e);
            }
        });
    }

    private String readSessionPointer() {
        try {
            if (Files.exists(sessionPointer)) {
                return Files.readString(sessionPointer).trim();
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to read the JFR repository pointer.", e);
        }
        return "";
    }

    private void locateCurrentSession() {
        try {
            Path base = JfrRepository.repositoryBase(
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Path.of(System.getProperty("java.io.tmpdir", ".")));
            currentSessionDir = JfrRepository.sessionDirForPid(base, ProcessHandle.current().pid()).orElse(null);
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to locate the active JFR repository.", e);
        }
    }

    private void persistCurrentSession() {
        if (currentSessionDir == null) {
            return;
        }
        try {
            Files.createDirectories(sessionPointer.getParent());
            Files.writeString(sessionPointer, currentSessionDir.toString());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to record the JFR repository location.", e);
        }
    }

    private boolean harvestPreviousRepository(String previousSession, Supplier<CapturePipeline> pipeline) {
        if (previousSession == null || previousSession.isEmpty()) {
            return false;
        }
        Path previousDir = Path.of(previousSession);
        if (previousDir.equals(currentSessionDir)) {
            return false;
        }
        List<Path> chunks;
        try {
            chunks = JfrRepository.chunkFiles(previousDir);
        } catch (Exception e) {
            return false;
        }
        if (chunks.isEmpty()) {
            return false;
        }
        Path merged = recoverDir.resolve("harvested-" + clock.millis() + ".jfr");
        boolean recovered = false;
        try {
            if (JfrRepository.merge(chunks, merged)) {
                recovered = pipeline.get()
                    .recoverFromRecording(merged, lastModifiedOrNow(previousDir))
                    .isPresent();
                if (recovered) {
                    logger.log(System.Logger.Level.INFO,
                        "Recovered an incident bundle from " + chunks.size()
                        + " JFR repository chunk(s) of a crashed run.");
                }
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to harvest the previous JFR repository.", e);
        } finally {
            try {
                Files.deleteIfExists(merged);
            } catch (Exception ignored) {
            }
            deleteRecursively(previousDir);
        }
        return recovered;
    }

    private Instant lastModifiedOrNow(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (Exception e) {
            return clock.instant();
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (java.nio.file.NoSuchFileException e) {
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to clean up harvested repository " + dir + ".", e);
        }
    }
}
