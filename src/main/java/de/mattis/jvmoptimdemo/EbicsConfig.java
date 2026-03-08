package de.mattis.jvmoptimdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * Laedt und kapselt die EBICS-Konfiguration.
 *
 * Primaere Quelle ist die Datei {@code /app/ebics/ebicsclient.config}.
 * Einzelne Werte koennen per Umgebungsvariable ueberschrieben werden
 * (z.B. {@code EBICS_URL} fuer Docker-Networking).
 *
 * <p>Die Config-Datei wird Zeile-fuer-Zeile geparst statt ueber
 * {@link Properties#load(Reader)}, weil die EK-Config-Dateien
 * Windows-Backslash-Pfade enthalten koennen, die {@code Properties.load}
 * als Escape-Sequenzen interpretieren wuerde.</p>
 */
@Component
public class EbicsConfig {

    private static final Logger log = LoggerFactory.getLogger(EbicsConfig.class);

    static final String EBICS_CONFIG_DIR = "/app/ebics";
    static final String CONFIG_FILE = EBICS_CONFIG_DIR + "/ebicsclient.config";

    private volatile Properties props;

    // ------------------------------------------------------------------
    // Typisierte Getter
    // ------------------------------------------------------------------

    public String getLicense()     { return get("ebicsKernelLicense", ""); }
    public String getUrl()         { return get("ebicsUrl", ""); }
    public String getHostId()      { return get("ebicsHostId", ""); }
    public String getCustomerId()  { return get("customerId", ""); }
    public String getUserId()      { return get("userId", ""); }

    public String getEbicsVersion() {
        String v = get("ebicsVersion", "H004");
        return (v == null || v.isEmpty()) ? "H004" : v;
    }

    public String getOrderType()    { return get("orderType", "JI1"); }
    private static final String DEFAULT_KEY_PASSWORD = "nosecret";

    public String getKeyPassword() {
        String pw = get("keyFileA00Password", DEFAULT_KEY_PASSWORD);
        if (DEFAULT_KEY_PASSWORD.equals(pw)) {
            log.warn("Using default key password '{}' — consider setting keyFileA00Password in config",
                    DEFAULT_KEY_PASSWORD);
        }
        return pw;
    }

    public boolean isVerifyTls()    { return Boolean.parseBoolean(get("verifyTls", "false")); }

    public String getUploadFilePath() {
        return get("file", EBICS_CONFIG_DIR + "/testfile.xml");
    }

    public String getConfigDir() { return EBICS_CONFIG_DIR; }

    // ------------------------------------------------------------------
    // Key-Dateien
    // ------------------------------------------------------------------

    /** Absoluter Pfad zur A00-Schluesseldatei. */
    public String getKeyFileA00() { return resolveKeyFile(get("keyFileA00", "ebicsclient_a00.p12")); }
    /** Absoluter Pfad zur E00-Schluesseldatei. */
    public String getKeyFileE00() { return resolveKeyFile(get("keyFileE00", "ebicsclient_e00.p12")); }
    /** Absoluter Pfad zur X00-Schluesseldatei. */
    public String getKeyFileX00() { return resolveKeyFile(get("keyFileX00", "ebicsclient_x00.p12")); }

    /**
     * Laedt eine PKCS#12-Schluesseldatei als Byte-Array.
     *
     * @param path absoluter Pfad zur .p12-Datei
     * @return Dateiinhalt als byte[]
     * @throws IOException wenn die Datei nicht existiert oder nicht lesbar ist
     */
    public byte[] loadPkcs12(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            throw new FileNotFoundException(
                    "PKCS#12 key file not found: " + path
                    + " (absolute=" + file.getAbsolutePath()
                    + ", cwd=" + System.getProperty("user.dir") + ")");
        }
        return Files.readAllBytes(file.toPath());
    }

    // ------------------------------------------------------------------
    // Properties laden
    // ------------------------------------------------------------------

    /** Gibt die geladenen Properties zurueck (lazy-init, thread-safe). */
    Properties getProperties() {
        if (props == null) {
            synchronized (this) {
                if (props == null) {
                    try {
                        props = loadConfig(CONFIG_FILE);
                    } catch (IOException e) {
                        log.error("Failed to load EBICS config from {}", CONFIG_FILE, e);
                        props = new Properties();
                    }
                }
            }
        }
        return props;
    }

    private String get(String key, String defaultValue) {
        return getProperties().getProperty(key, defaultValue);
    }

    /**
     * Liest die Config-Datei Zeile-fuer-Zeile.
     * Anschliessend werden Umgebungsvariablen als Overrides angewendet.
     */
    static Properties loadConfig(String path) throws IOException {
        Properties p = new Properties();
        File file = new File(path);

        if (!file.exists()) {
            log.warn("Config file not found: {}, using environment variables only", path);
            // setzt die config in einem property objekt nur mit umgebungsvariablen, damit die getProperty-Methoden auch ohne config file funktionieren
            applyAllEnvOverrides(p);
            return p;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        p.setProperty(key, value);
                    }
                }
            }
        }

        applyAllEnvOverrides(p);
        return p;
    }

    // ------------------------------------------------------------------
    // Env-Overrides
    // ------------------------------------------------------------------

    private static void applyAllEnvOverrides(Properties p) {
        applyEnvOverride(p, "EBICS_URL", "ebicsUrl");
        applyEnvOverride(p, "EBICS_LICENSE", "ebicsKernelLicense");
        applyEnvOverride(p, "EBICS_HOST_ID", "ebicsHostId");
        applyEnvOverride(p, "EBICS_CUSTOMER_ID", "customerId");
        applyEnvOverride(p, "EBICS_USER_ID", "userId");
    }

    private static void applyEnvOverride(Properties p, String envVar, String propKey) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            String old = p.getProperty(propKey, "");
            p.setProperty(propKey, value);
            if (!value.equals(old)) {
                log.info("ENV override: {} -> {} (was: {})",
                        envVar, propKey, old.isEmpty() ? "(empty)" : old);
            }
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    /**
     * Loest einen relativen Key-Datei-Pfad gegen {@link #EBICS_CONFIG_DIR} auf.
     * Absolute Pfade werden unveraendert zurueckgegeben.
     */
    String resolveKeyFile(String keyFile) {
        if (keyFile == null || keyFile.isEmpty()) return keyFile;
        File f = new File(keyFile);
        if (f.isAbsolute()) return keyFile;
        File resolved = new File(EBICS_CONFIG_DIR, keyFile);
        if (resolved.exists()) return resolved.getAbsolutePath();
        return keyFile;
    }

    /**
     * Maskiert einen sensiblen Wert fuer die Log-Ausgabe.
     * Zeigt nur die ersten 3 und letzten 3 Zeichen, dazwischen {@code ***}.
     *
     * @param value zu maskierender Wert (darf {@code null} sein)
     * @return maskierter String oder {@code "(not set)"}
     */
    public static String maskSensitive(String value) {
        if (value == null || value.isEmpty()) return "(not set)";
        if (value.length() <= 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }
}
