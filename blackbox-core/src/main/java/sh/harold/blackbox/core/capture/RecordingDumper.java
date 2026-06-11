package sh.harold.blackbox.core.capture;

import java.nio.file.Path;

@FunctionalInterface
public interface RecordingDumper {
    Path dump(Path target) throws Exception;
}
