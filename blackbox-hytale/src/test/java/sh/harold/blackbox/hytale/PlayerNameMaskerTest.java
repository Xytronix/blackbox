package sh.harold.blackbox.hytale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerNameMaskerTest {

    @Test
    void sameNameGetsStableToken() {
        PlayerNameMasker masker = new PlayerNameMasker();

        String first = masker.maskName("Steve");
        String second = masker.maskName("Steve");
        String other = masker.maskName("Alex");

        assertEquals(first, second);
        assertFalse(first.equals(other));
        assertTrue(first.startsWith("player-"));
    }

    @Test
    void nameAndUuidShareToken() {
        PlayerNameMasker masker = new PlayerNameMasker();
        UUID uuid = UUID.fromString("7d15a022-4875-44f5-9f0b-73f1f4ecbdee");
        masker.remember("Steve", uuid);

        String token = masker.maskName("Steve");
        String text = masker.maskText("UUID of Steve is 7d15a022-4875-44f5-9f0b-73f1f4ecbdee");

        assertEquals("UUID of " + token + " is " + token, text);
    }

    @Test
    void maskTextLeavesUnknownTextAlone() {
        PlayerNameMasker masker = new PlayerNameMasker();
        masker.maskName("Steve");

        String text = "world 7d15a022-4875-44f5-9f0b-73f1f4ecbdee loaded by Herobrine";

        assertEquals(text, masker.maskText(text));
    }

    @Test
    void maskTextRespectsWordBoundaries() {
        PlayerNameMasker masker = new PlayerNameMasker();
        String token = masker.maskName("Sam");

        assertEquals(token + " met Samuel", masker.maskText("Sam met Samuel"));
    }

    @Test
    void learnsNamesFromDisconnectLogLines() {
        PlayerNameMasker masker = new PlayerNameMasker();

        String masked = masker.maskText(
            "Disconnecting Steve at /203.0.113.7:1234 (SNI: x) with the message: bye\n"
            + "Steve placed a block");

        assertFalse(masked.contains("Steve"));
        String token = masker.maskName("Steve");
        assertEquals("Disconnecting " + token + " at /203.0.113.7:1234 (SNI: x) with the message: bye\n"
            + token + " placed a block", masked);
    }

    @Test
    void doesNotLearnAddressesFromDisconnectLines() {
        PlayerNameMasker masker = new PlayerNameMasker();

        String line = "Disconnecting /203.0.113.7:1234 (SNI: x) with the message: bye";

        assertEquals(line, masker.maskText(line));
    }

    @Test
    void learnsNameAndUuidFromUniversePlayerLine() {
        PlayerNameMasker masker = new PlayerNameMasker();

        String masked = masker.maskText(
            "[Universe|P] Player 'Steve' (bd97e661-1c0e-40c8-8863-b37f97b66447) could not join"
            + " - no default world configured\n"
            + "later: Steve and bd97e661-1c0e-40c8-8863-b37f97b66447 again");

        assertFalse(masked.contains("Steve"));
        assertFalse(masked.contains("bd97e661"));
        String token = masker.maskName("Steve");
        assertTrue(masked.contains("Player '" + token + "' (" + token + ")"));
        assertTrue(masked.contains("later: " + token + " and " + token + " again"));
    }

    @Test
    void learnsIdentityFromLoginTimingLines() {
        PlayerNameMasker masker = new PlayerNameMasker();

        String masked = masker.maskText(
            "[LoginTiming] [{Setup(QuicConnectionAddress{connId=4279a}), Steve,"
            + " bd97e661-1c0e-40c8-8863-b37f97b66447}] Entering stage 'setup:assets-request'\n"
            + "[LoginTiming] [{Playing(QuicConnectionAddress{connId=a3cb}),"
            + " bd97e661-1c0e-40c8-8863-b37f97b66447, Steve}] Add To Universe took 12ms\n"
            + "[LoginTiming] [{Playing(QuicConnectionAddress{connId=a3cb}), null player}] Load Player Config");

        assertFalse(masked.contains("Steve"));
        assertFalse(masked.contains("bd97e661"));
        assertTrue(masked.contains("null player"));
    }

    @Test
    void blankNameIsUnknown() {
        PlayerNameMasker masker = new PlayerNameMasker();

        assertEquals("unknown", masker.maskName(null));
        assertEquals("unknown", masker.maskName("  "));
    }
}
