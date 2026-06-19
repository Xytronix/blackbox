package sh.harold.blackbox.core.env;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class ThreadDumperTest {

    @Test
    void dumpListsThreadsWithHeaderAndStackFrames() {
        String dump = ThreadDumper.dump();
        assertTrue(dump.startsWith("threads="), dump);
        assertTrue(dump.contains(Thread.currentThread().getName()), dump);
        assertTrue(dump.contains("state="), dump);
        assertTrue(dump.contains("    at "), dump);
    }

    @Test
    void dumpReportsOwnableSynchronizers() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            String dump = ThreadDumper.dump();
            assertTrue(dump.contains("Locked ownable synchronizers"), dump);
            assertTrue(dump.contains("ReentrantLock"), dump);
        } finally {
            lock.unlock();
        }
    }
}
