package sh.harold.blackbox.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.harold.blackbox.core.incident.DiagnosticSection;

class ThreadDumpProgressTest {

    private static String dump(String threadBody) {
        return "threads=2\n\n" + threadBody;
    }

    @Test
    void flagsRunnableThreadStuckAtSameFrame() {
        String body = "\"worker\" #10 state=RUNNABLE\n    at com.x.Y.loop(Y.java:5)\n    at com.x.Y.run(Y.java:1)\n";
        List<DiagnosticSection> sections =
            ThreadDumpProgress.appendTo(List.of(), List.of(dump(body), dump(body)));

        assertEquals(1, sections.size());
        DiagnosticSection s = sections.get(0);
        assertEquals("Thread progress", s.title());
        assertTrue(s.entries().get("Verdict").contains("no progress"), s.entries().toString());
        assertTrue(s.entries().containsKey("worker"), s.entries().toString());
    }

    @Test
    void reportsProgressWhenTopFrameChanges() {
        String d1 = dump("\"worker\" #10 state=RUNNABLE\n    at com.x.Y.a(Y.java:5)\n");
        String d2 = dump("\"worker\" #10 state=RUNNABLE\n    at com.x.Y.b(Y.java:9)\n");
        List<DiagnosticSection> sections = ThreadDumpProgress.appendTo(List.of(), List.of(d1, d2));

        assertEquals(1, sections.size());
        assertTrue(sections.get(0).entries().get("Verdict").contains("advanced"), sections.toString());
        assertFalse(sections.get(0).entries().containsKey("worker"), sections.toString());
    }

    @Test
    void ignoresIdleWaitingThreads() {
        String body = "\"pool-1\" #11 state=WAITING\n    at jdk.internal.misc.Unsafe.park(Unsafe.java:1)\n";
        List<DiagnosticSection> sections =
            ThreadDumpProgress.appendTo(List.of(), List.of(dump(body), dump(body)));

        assertEquals(1, sections.size());
        assertTrue(sections.get(0).entries().get("Verdict").contains("advanced"), sections.toString());
        assertFalse(sections.get(0).entries().containsKey("pool-1"), sections.toString());
    }

    @Test
    void noSectionForFewerThanTwoDumps() {
        assertEquals(List.of(), ThreadDumpProgress.appendTo(List.of(), List.of("one dump")));
    }
}
