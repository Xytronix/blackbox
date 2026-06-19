package sh.harold.blackbox.hytale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HytaleServerSettingsTest {

    @Test
    void masksNonEmptyPassword() {
        String json = "{\n  \"ServerName\": \"Hytale Server\",\n  \"Password\": \"hunter2\",\n  \"MaxPlayers\": 1000\n}";
        String out = HytaleServerSettings.redact(json);
        assertEquals("{\n  \"ServerName\": \"Hytale Server\",\n  \"Password\": \"<redacted>\",\n  \"MaxPlayers\": 1000\n}", out);
    }

    @Test
    void leavesEmptyPasswordUntouched() {
        String json = "{\"Password\": \"\", \"MOTD\": \"\"}";
        assertEquals(json, HytaleServerSettings.redact(json));
    }

    @Test
    void collapsesAuthCredentialStoreObject() {
        String json = "{\"AuthCredentialStore\": {\"Type\": \"Encrypted\", \"Inner\": {\"Key\": \"abc}{\\\"\"}}, \"Mods\": {\"Server:Prefab2.0\": {\"Enabled\": true}}}";
        String out = HytaleServerSettings.redact(json);
        assertEquals("{\"AuthCredentialStore\": \"<redacted>\", \"Mods\": {\"Server:Prefab2.0\": {\"Enabled\": true}}}", out);
    }
}
