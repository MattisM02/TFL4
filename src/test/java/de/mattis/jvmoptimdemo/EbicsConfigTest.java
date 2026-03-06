package de.mattis.jvmoptimdemo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests fuer EbicsConfig.
 *
 * Testet Config-Parsing, Env-Overrides, Key-Datei-Aufloesung
 * und die maskSensitive-Hilfsmethode.
 */
class EbicsConfigTest {

    // ==================== maskSensitive ====================

    @Test
    void maskSensitive_null_returnsNotSet() {
        assertEquals("(not set)", EbicsConfig.maskSensitive(null));
    }

    @Test
    void maskSensitive_empty_returnsNotSet() {
        assertEquals("(not set)", EbicsConfig.maskSensitive(""));
    }

    @Test
    void maskSensitive_short_returnsStars() {
        assertEquals("***", EbicsConfig.maskSensitive("abc"));
        assertEquals("***", EbicsConfig.maskSensitive("abcdef"));
    }

    @Test
    void maskSensitive_long_masksMiddle() {
        String result = EbicsConfig.maskSensitive("my-secret-license-key");
        assertEquals("my-***key", result);
    }

    @Test
    void maskSensitive_sevenChars_showsEnds() {
        String result = EbicsConfig.maskSensitive("1234567");
        assertEquals("123***567", result);
    }

    // ==================== loadConfig ====================

    @Test
    void loadConfig_parsesKeyValuePairs(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("test.config");
        Files.writeString(configFile, """
                ebicsUrl=https://localhost:7070/
                ebicsHostId=HOST
                customerId=KUNDE
                userId=TEILN
                """);

        Properties props = EbicsConfig.loadConfig(configFile.toString());

        assertEquals("https://localhost:7070/", props.getProperty("ebicsUrl"));
        assertEquals("HOST", props.getProperty("ebicsHostId"));
        assertEquals("KUNDE", props.getProperty("customerId"));
        assertEquals("TEILN", props.getProperty("userId"));
    }

    @Test
    void loadConfig_skipsCommentsAndEmptyLines(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("test.config");
        Files.writeString(configFile, """
                # This is a comment
                
                ebicsUrl=https://example.com/
                # Another comment
                ebicsHostId=H1
                """);

        Properties props = EbicsConfig.loadConfig(configFile.toString());

        assertEquals("https://example.com/", props.getProperty("ebicsUrl"));
        assertEquals("H1", props.getProperty("ebicsHostId"));
        assertEquals(2, props.size());
    }

    @Test
    void loadConfig_skipsEmptyValues(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("test.config");
        Files.writeString(configFile, """
                ebicsUrl=https://example.com/
                ebicsVersion=
                orderType=CCT
                """);

        Properties props = EbicsConfig.loadConfig(configFile.toString());

        assertEquals("https://example.com/", props.getProperty("ebicsUrl"));
        assertNull(props.getProperty("ebicsVersion"));
        assertEquals("CCT", props.getProperty("orderType"));
    }

    @Test
    void loadConfig_missingFile_returnsEmptyProps() throws IOException {
        Properties props = EbicsConfig.loadConfig("/nonexistent/path/config");
        // Sollte nicht werfen, sondern leere Props mit Env-Overrides zurueckgeben
        assertNotNull(props);
    }

    @Test
    void loadConfig_preservesBackslashPaths(@TempDir Path tempDir) throws IOException {
        // Kernpunkt: Wir parsen Zeile-fuer-Zeile, nicht Properties.load(),
        // weil Windows-Pfade wie D:\TravicLink\data sonst kaputt gehen
        Path configFile = tempDir.resolve("test.config");
        Files.writeString(configFile, """
                file=D:\\TravicLink\\data\\input\\testfile.txt
                """);

        Properties props = EbicsConfig.loadConfig(configFile.toString());

        assertEquals("D:\\TravicLink\\data\\input\\testfile.txt", props.getProperty("file"));
    }

    // ==================== resolveKeyFile ====================

    @Test
    void resolveKeyFile_absolutePath_returnsUnchanged() {
        EbicsConfig config = new EbicsConfig();
        String result = config.resolveKeyFile("/absolute/path/key.p12");
        assertEquals("/absolute/path/key.p12", result);
    }

    @Test
    void resolveKeyFile_null_returnsNull() {
        EbicsConfig config = new EbicsConfig();
        assertNull(config.resolveKeyFile(null));
    }

    @Test
    void resolveKeyFile_empty_returnsEmpty() {
        EbicsConfig config = new EbicsConfig();
        assertEquals("", config.resolveKeyFile(""));
    }
}
