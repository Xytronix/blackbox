package sh.harold.blackbox.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextRedactorTest {

    @Test
    void defaultPatternsRedactAddressesAndWebhooks() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);
        String input = "password=hunter2 "
            + "ip=203.0.113.7 "
            + "world=7d15a022-4875-44f5-9f0b-73f1f4ecbdee "
            + "hook=https://discord.com/api/webhooks/12345678901234567/abcDEF_ghi";

        String redacted = redactor.redact(input);

        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("hunter2"));
        assertTrue(redacted.contains("7d15a022-4875-44f5-9f0b-73f1f4ecbdee"));
        assertFalse(redacted.contains("203.0.113.7"));
        assertFalse(redacted.contains("12345678901234567"));
    }

    @Test
    void defaultPatternsRedactJsonPasswordValueButNotEmpty() {
        TextRedactor redactor = new TextRedactor(TextRedactor.DEFAULT_PATTERNS);

        assertFalse(redactor.redact("{\"Password\": \"hunter2\"}").contains("hunter2"));
        assertTrue(redactor.redact("{\"Password\": \"hunter2\"}").contains("\"Password\": \"[REDACTED]\""));
        assertEquals("{\"Password\": \"\"}", redactor.redact("{\"Password\": \"\"}"));
    }

    @Test
    void usesProvidedPatternsLiterallyWithoutMergingDefaults() {
        TextRedactor redactor = new TextRedactor(List.of("CUSTOM_SECRET_[A-Z0-9]+"));
        String input = "ip=192.168.10.20 custom=CUSTOM_SECRET_ABC123";

        String redacted = redactor.redact(input);

        assertTrue(redacted.contains("192.168.10.20"));
        assertFalse(redacted.contains("CUSTOM_SECRET_ABC123"));
    }

    @Test
    void invalidPatternIsSkippedWithoutBreakingOthers() {
        TextRedactor redactor = new TextRedactor(List.of("[", "password=\\S+"));
        String redacted = redactor.redact("password=swordfish");

        assertFalse(redacted.contains("swordfish"));
    }
}
