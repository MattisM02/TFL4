package de.mattis.jvmoptimdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST-Controller fuer EBICS-Operationen.
 *
 * Nutzt die Travic EBICS Kernel API (EK 4.0.9) fuer echte EBICS-Uploads.
 * Die Konfiguration wird aus /app/ebics/ebicsclient.config gelesen.
 * PKCS#12 Schluessel liegen unter /app/ebics/.
 *
 * Falls die EK-Klassen nicht im Classpath sind, wird in einen
 * Simulationsmodus gewechselt (fuer Builds ohne EK-JARs).
 *
 * Betriebsmodi:
 * - REAL:       EK-Klassen verfuegbar, echte EBICS-Kommunikation
 * - SIMULATION: EK-Klassen nicht im Classpath, simulierte Operationen (50ms sleep + 8KB alloc)
 *
 * Jede Response enthaelt ein "mode"-Feld (REAL oder SIMULATION), damit
 * in Benchmark-Ergebnissen nie versehentlich Simulationsdaten als echte Messwerte ausgewertet werden.
 */
@RestController
@RequestMapping("/ebics")
public class EkController {

    private static final Logger log = LoggerFactory.getLogger(EkController.class);

    private static final String EBICS_CONFIG_DIR = "/app/ebics";
    private static final String CONFIG_FILE = EBICS_CONFIG_DIR + "/ebicsclient.config";

    /**
     * Betriebsmodus: REAL (echte EK-Kommunikation) oder SIMULATION (ohne EK-JARs).
     */
    private enum EkMode { REAL, SIMULATION }

    private final AtomicLong requestCounter = new AtomicLong(0);
    private final AtomicLong uploadCounter = new AtomicLong(0);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // EK state - initialized lazily
    private volatile Object /* ServerParameters */ serverParams;
    private volatile Object /* UserParameters */ userParams;
    private volatile Object /* Signer */ x00Signer;
    private volatile Object /* Signer */ a00Signer;
    private volatile Object /* Decrypter */ e00Decrypter;
    private volatile String ebicsVersion;
    private volatile String orderType;
    private volatile String uploadFilePath;
    private volatile EkMode mode = EkMode.SIMULATION;
    private volatile String initError = null;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("mode", mode.name());
        result.put("ekAvailable", checkEkAvailable());
        result.put("ekInitialized", initialized.get());
        if (initError != null) {
            result.put("initError", initError);
        }
        result.put("totalRequests", requestCounter.get());
        return result;
    }

    @GetMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam(name = "n", defaultValue = "1") int n
    ) {
        requestCounter.incrementAndGet();
        long startTime = System.nanoTime();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("mode", mode.name());
        result.put("requested", n);

        try {
            ensureInitialized();
            for (int i = 0; i < n; i++) {
                performEkUpload();
            }
            result.put("uploadCount", uploadCounter.addAndGet(n));
        } catch (Exception e) {
            log.error("EK upload error", e);
            result.put("status", "error");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        result.put("durationMs", durationMs);
        // Modus nochmal am Ende setzen, falls sich durch ensureInitialized() geaendert
        result.put("mode", mode.name());

        return result;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode.name());
        result.put("totalRequests", requestCounter.get());
        result.put("totalUploads", uploadCounter.get());
        result.put("ekAvailable", checkEkAvailable());
        result.put("ekInitialized", initialized.get());
        return result;
    }

    // ==================== EK Integration ====================

    private boolean checkEkAvailable() {
        try {
            Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.Upload");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Lazy-Initialisierung: Laedt Konfiguration, Schluessel, ruft HPB ab.
     * Thread-safe durch double-checked locking.
     *
     * Bei Fehlern werden ausfuehrliche Diagnoseinformationen geloggt:
     * - Konfigurationswerte (sensible Werte maskiert)
     * - Pfade zu Key-Dateien und ob sie existieren
     * - Welche EK-Klassen gefunden/nicht gefunden wurden
     */
    private void ensureInitialized() throws Exception {
        if (initialized.get()) return;

        synchronized (this) {
            if (initialized.get()) return;

            if (!checkEkAvailable()) {
                mode = EkMode.SIMULATION;
                initError = "EK classes not on classpath - simulation mode active. " +
                        "To use REAL mode, add EK JARs (ebicskernel.jar, ebicscommon.jar, ebics_xml.jar) to classpath.";
                log.warn("EK SIMULATION mode: EK classes not found on classpath");
                logEkClasspathDiagnostics();
                initialized.set(true);
                return;
            }

            try {
                initializeEk();
                mode = EkMode.REAL;
                log.info("EK initialized successfully in REAL mode");
            } catch (Exception e) {
                initError = buildInitErrorDiagnostics(e);
                log.error("EK initialization FAILED - detailed diagnostics follow");
                logInitFailureDiagnostics(e);
                throw e;
            }
            initialized.set(true);
        }
    }

    /**
     * Baut eine ausfuehrliche Fehlerbeschreibung fuer die initError-Variable.
     */
    private String buildInitErrorDiagnostics(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());

        // Ursache anfuegen, falls vorhanden
        Throwable cause = e.getCause();
        if (cause != null) {
            sb.append(" | Caused by: ").append(cause.getClass().getSimpleName())
              .append(": ").append(cause.getMessage());
        }

        return sb.toString();
    }

    /**
     * Loggt ausfuehrliche Diagnoseinformationen bei Init-Fehlern.
     * Sensible Werte (Passwoerter, Lizenzen) werden maskiert.
     */
    private void logInitFailureDiagnostics(Exception e) {
        log.error("=== EK INIT FAILURE DIAGNOSTICS ===");
        log.error("Exception: {} - {}", e.getClass().getName(), e.getMessage());
        if (e.getCause() != null) {
            log.error("Root cause: {} - {}", e.getCause().getClass().getName(), e.getCause().getMessage());
        }

        // Config-Datei pruefen
        File configFile = new File(CONFIG_FILE);
        log.error("Config file: {} (exists={}, readable={}, size={})",
                CONFIG_FILE, configFile.exists(), configFile.canRead(),
                configFile.exists() ? configFile.length() : "N/A");

        // Config-Werte loggen (sensible maskiert)
        try {
            Properties props = readConfig(CONFIG_FILE);
            log.error("Config values:");
            log.error("  ebicsUrl = {}", props.getProperty("ebicsUrl", "(not set)"));
            log.error("  ebicsHostId = {}", props.getProperty("ebicsHostId", "(not set)"));
            log.error("  customerId = {}", props.getProperty("customerId", "(not set)"));
            log.error("  userId = {}", props.getProperty("userId", "(not set)"));
            log.error("  ebicsVersion = {}", props.getProperty("ebicsVersion", "(not set)"));
            log.error("  orderType = {}", props.getProperty("orderType", "(not set)"));
            log.error("  ebicsKernelLicense = {}", maskSensitive(props.getProperty("ebicsKernelLicense")));
            log.error("  keyFileA00Password = {}", maskSensitive(props.getProperty("keyFileA00Password")));
            log.error("  verifyTls = {}", props.getProperty("verifyTls", "(not set)"));
            log.error("  verifyBankKeys = {}", props.getProperty("verifyBankKeys", "(not set)"));
        } catch (Exception configEx) {
            log.error("Could not read config for diagnostics: {}", configEx.getMessage());
        }

        // Key-Dateien pruefen
        logKeyFileDiagnostics("keyFileA00", "ebicsclient_a00.p12");
        logKeyFileDiagnostics("keyFileE00", "ebicsclient_e00.p12");
        logKeyFileDiagnostics("keyFileX00", "ebicsclient_x00.p12");

        // Upload-Testdatei pruefen
        String testFile = EBICS_CONFIG_DIR + "/testfile.xml";
        File tf = new File(testFile);
        log.error("Test file: {} (exists={}, size={})",
                testFile, tf.exists(), tf.exists() ? tf.length() : "N/A");

        // EBICS-Verzeichnis-Inhalt
        File ebicsDir = new File(EBICS_CONFIG_DIR);
        if (ebicsDir.exists() && ebicsDir.isDirectory()) {
            String[] files = ebicsDir.list();
            log.error("EBICS directory contents: {}", files != null ? Arrays.toString(files) : "(empty or error)");
        } else {
            log.error("EBICS directory {} does not exist or is not a directory", EBICS_CONFIG_DIR);
        }

        log.error("=== END DIAGNOSTICS ===");
    }

    /**
     * Loggt Diagnostik fuer eine einzelne Key-Datei.
     */
    private void logKeyFileDiagnostics(String configKey, String defaultFilename) {
        try {
            Properties props = readConfig(CONFIG_FILE);
            String configured = props.getProperty(configKey, defaultFilename);
            String resolved = resolveKeyFile(configured);
            File f = new File(resolved);
            log.error("  {} = {} -> resolved={} (exists={}, size={})",
                    configKey, configured, resolved, f.exists(),
                    f.exists() ? f.length() : "N/A");
        } catch (Exception ex) {
            log.error("  {} diagnostics failed: {}", configKey, ex.getMessage());
        }
    }

    /**
     * Loggt, welche EK-Klassen im Classpath gefunden werden.
     */
    private void logEkClasspathDiagnostics() {
        String[] criticalClasses = {
                "de.ppi.fis.travic.ebics.kernel.api.main.Upload",
                "de.ppi.fis.travic.ebics.kernel.api.main.HPB",
                "de.ppi.fis.travic.ebics.kernel.api.main.Configuration",
                "de.ppi.fis.travic.ebics.kernel.api.main.CryptoUtility",
                "de.ppi.fis.travic.ebics.kernel.api.parameters.ServerParameters"
        };

        log.warn("EK classpath diagnostics:");
        for (String className : criticalClasses) {
            try {
                Class.forName(className);
                log.warn("  {} = FOUND", className);
            } catch (ClassNotFoundException e) {
                log.warn("  {} = NOT FOUND", className);
            }
        }

        // java.library.path pruefen (fuer native EK-Bibliotheken)
        log.warn("  java.library.path = {}", System.getProperty("java.library.path", "(not set)"));
    }

    /**
     * Maskiert einen sensiblen Wert fuer die Log-Ausgabe.
     * Zeigt nur die ersten 3 und letzten 3 Zeichen.
     *
     * @param value zu maskierender Wert
     * @return maskierter Wert oder "(not set)"
     */
    static String maskSensitive(String value) {
        if (value == null || value.isEmpty()) return "(not set)";
        if (value.length() <= 6) return "***";
        return value.substring(0, 3) + "***" + value.substring(value.length() - 3);
    }

    /**
     * Fuehrt die vollstaendige EK-Initialisierung durch:
     * 1. Lizenz setzen
     * 2. Konfiguration lesen
     * 3. PKCS#12 Schluessel laden
     * 4. HPB (Bank-Keys abrufen)
     * 5. ServerParameters mit Bank-Keys erstellen
     */
    private void initializeEk() throws Exception {
        log.info("Initializing EBICS Kernel from config: {}", CONFIG_FILE);

        // Config lesen
        Properties props = readConfig(CONFIG_FILE);

        String license = props.getProperty("ebicsKernelLicense");
        String url = props.getProperty("ebicsUrl");
        String hostId = props.getProperty("ebicsHostId");
        String customerId = props.getProperty("customerId");
        String userId = props.getProperty("userId");
        ebicsVersion = props.getProperty("ebicsVersion", "H004");
        if (ebicsVersion.isEmpty()) ebicsVersion = "H004";
        orderType = props.getProperty("orderType", "JI1");
        uploadFilePath = props.getProperty("file", EBICS_CONFIG_DIR + "/testfile.xml");
        boolean verifyTls = Boolean.parseBoolean(props.getProperty("verifyTls", "false"));
        boolean verifyBankKeys = Boolean.parseBoolean(props.getProperty("verifyBankKeys", "false"));
        String keyPassword = props.getProperty("keyFileA00Password", "nosecret");

        // Schluessel-Dateien (relativ zu EBICS_CONFIG_DIR)
        String keyFileA00 = resolveKeyFile(props.getProperty("keyFileA00", "ebicsclient_a00.p12"));
        String keyFileE00 = resolveKeyFile(props.getProperty("keyFileE00", "ebicsclient_e00.p12"));
        String keyFileX00 = resolveKeyFile(props.getProperty("keyFileX00", "ebicsclient_x00.p12"));

        log.info("EK config: url={}, hostId={}, customerId={}, userId={}, version={}, orderType={}",
                url, hostId, customerId, userId, ebicsVersion, orderType);
        log.info("EK key files: A00={} (exists={}), E00={} (exists={}), X00={} (exists={})",
                keyFileA00, new File(keyFileA00).exists(),
                keyFileE00, new File(keyFileE00).exists(),
                keyFileX00, new File(keyFileX00).exists());

        // === Reflection-basierte Aufrufe der EK-API ===
        // (Wir nutzen Reflection damit die Anwendung auch ohne EK-JARs kompiliert)

        // 1. Lizenz setzen
        Class<?> configClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.Configuration");
        configClass.getMethod("setLicense", String.class).invoke(null, license);
        log.info("License set successfully");

        // 2. Sprache setzen
        configClass.getMethod("setLanguage", Locale.class).invoke(null, Locale.ENGLISH);

        // 3. PKCS#12 Schluessel laden
        Class<?> cryptoUtilClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.CryptoUtility");
        byte[] a00Pkcs12 = loadPkcs12(keyFileA00);
        byte[] e00Pkcs12 = loadPkcs12(keyFileE00);
        byte[] x00Pkcs12 = loadPkcs12(keyFileX00);

        log.info("Loaded PKCS#12 keys: A00={}bytes, E00={}bytes, X00={}bytes",
                a00Pkcs12.length, e00Pkcs12.length, x00Pkcs12.length);

        // Private Keys extrahieren
        Object a00PrivKey = cryptoUtilClass.getMethod("getPrivateKeyFromPKCS12", byte[].class, String.class)
                .invoke(null, a00Pkcs12, keyPassword);
        byte[] a00Pkcs8 = (byte[]) a00PrivKey.getClass().getMethod("getPKCS8KeyBytes").invoke(a00PrivKey);

        Object x00PrivKey = cryptoUtilClass.getMethod("getPrivateKeyFromPKCS12", byte[].class, String.class)
                .invoke(null, x00Pkcs12, keyPassword);
        byte[] x00Pkcs8 = (byte[]) x00PrivKey.getClass().getMethod("getPKCS8KeyBytes").invoke(x00PrivKey);

        Object e00PrivKey = cryptoUtilClass.getMethod("getPrivateKeyFromPKCS12", byte[].class, String.class)
                .invoke(null, e00Pkcs12, keyPassword);
        byte[] e00Pkcs8 = (byte[]) e00PrivKey.getClass().getMethod("getPKCS8KeyBytes").invoke(e00PrivKey);

        // Signer/Decrypter erstellen
        a00Signer = cryptoUtilClass.getMethod("createA006Signer", byte[].class).invoke(null, a00Pkcs8);
        x00Signer = cryptoUtilClass.getMethod("createX002Signer", byte[].class).invoke(null, x00Pkcs8);
        e00Decrypter = cryptoUtilClass.getMethod("createE002Decrypter", byte[].class).invoke(null, e00Pkcs8);
        log.info("Created A006 signer, X002 signer, E002 decrypter");

        // 4. TLS-Check + ServerParameters ohne Bank-Keys (fuer HPB)
        Class<?> serverParamsClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.parameters.ServerParameters");
        String ebicsH000 = (String) serverParamsClass.getField("EBICS_VERSION_H000").get(null);

        // TLS-Zertifikate pruefen
        String[] tlsCerts = null;
        if (!verifyTls) {
            Class<?> certClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.Certificate");
            Object cert = certClass.getMethod("create",
                    serverParamsClass).invoke(null,
                    serverParamsClass.getConstructor(
                            String.class, String.class, boolean.class, String[].class,
                            String.class, byte[].class, byte[].class,
                            String.class, int.class, String.class, String.class,
                            String.class, String.class, boolean.class
                    ).newInstance(ebicsH000, url, false, null,
                            hostId, null, null,
                            null, 0, null, null,
                            null, null, true));
            try {
                certClass.getMethod("verify").invoke(cert);
            } catch (Exception e) {
                // TLS nicht vertrauenswuerdig, Zertifikat manuell vertrauen
                Class<?> pemClass = Class.forName("de.ppi.fis.travic.ebics.common.crypt.PEMConverter");
                Object pemConverter = pemClass.getField("CERTIFICATE_CONVERTER").get(null);
                java.security.cert.X509Certificate[] chain =
                        (java.security.cert.X509Certificate[]) certClass.getMethod("getCertificateChain").invoke(cert);
                String pem = (String) pemConverter.getClass().getMethod("encode", byte[].class)
                        .invoke(pemConverter, chain[0].getEncoded());
                tlsCerts = new String[]{pem};
                log.info("TLS certificate not trusted by JVM, using manual trust");
            }
        }

        // 5. ServerParameters fuer HPB (ohne Bank-Keys)
        Class<?> tlsVersionClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.model.TlsVersion");
        Object tlsAutomatic = tlsVersionClass.getField("AUTOMATIC").get(null);

        Object hpbServerParams = serverParamsClass.getConstructor(
                String.class, String.class, tlsVersionClass, boolean.class, String[].class,
                String.class, byte[].class, byte[].class,
                String.class, int.class, String.class, String.class,
                String.class, String.class, boolean.class,
                String.class, String.class, String.class
        ).newInstance(
                ebicsVersion, url, tlsAutomatic, true, tlsCerts,
                hostId, null, null,
                null, 0, null, null,
                null, null, true,
                null, null, null
        );

        // UserParameters
        Class<?> userParamsClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.parameters.UserParameters");
        userParams = userParamsClass.getConstructor(String.class, String.class, String.class)
                .newInstance(userId, customerId, "0000");

        // 6. HPB - Bank-Keys abrufen
        log.info("Calling HPB to obtain bank keys...");
        Class<?> hpbClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.HPB");
        Class<?> signerClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.crypt.Signer");
        Class<?> decrypterClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.crypt.Decrypter");

        Object hpb = hpbClass.getMethod("create",
                serverParamsClass, userParamsClass, signerClass, decrypterClass
        ).invoke(null, hpbServerParams, userParams, x00Signer, e00Decrypter);

        Object hpbResult = hpb.getClass().getMethod("send").invoke(hpb);
        byte[] bankAuthKey = (byte[]) hpbResult.getClass().getMethod("getAuthenticationKey").invoke(hpbResult);
        byte[] bankEncKey = (byte[]) hpbResult.getClass().getMethod("getEncryptionKey").invoke(hpbResult);

        String[] authCerts = (String[]) hpbResult.getClass().getMethod("getAuthenticationCertificates").invoke(hpbResult);
        String[] encCerts = (String[]) hpbResult.getClass().getMethod("getEncryptionCertificates").invoke(hpbResult);
        String bankAuthCert = (authCerts != null && authCerts.length > 0) ? authCerts[0] : null;
        String bankEncCert = (encCerts != null && encCerts.length > 0) ? encCerts[0] : null;

        log.info("HPB successful, obtained bank keys");

        // 7. Finale ServerParameters mit Bank-Keys
        serverParams = serverParamsClass.getConstructor(
                String.class, String.class, tlsVersionClass, boolean.class, String[].class,
                String.class, byte[].class, byte[].class,
                String.class, int.class, String.class, String.class,
                String.class, String.class, boolean.class,
                String.class, String.class, String.class
        ).newInstance(
                ebicsVersion, url, tlsAutomatic, true, tlsCerts,
                hostId, bankAuthKey, bankEncKey,
                null, 0, null, null,
                null, null, true,
                null, bankEncCert, bankAuthCert
        );

        log.info("EBICS Kernel fully initialized and ready for uploads (REAL mode)");
    }

    private void performEkUpload() throws Exception {
        if (mode != EkMode.REAL) {
            log.debug("EK SIMULATION mode - running simulated upload");
            simulateEkOperation();
            return;
        }

        log.debug("Performing real EBICS upload (REAL mode)");

        // Testdatei erstellen falls nicht vorhanden
        File dataFile = new File(uploadFilePath);
        if (!dataFile.exists()) {
            // Generiere eine realistische SEPA-Testdatei
            dataFile = createTestFile();
        }

        // OrderSignature erstellen
        Class<?> sigCreatorClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.OrderSignatureCreator");
        Class<?> signerClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.crypt.Signer");
        Class<?> serverParamsClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.parameters.ServerParameters");
        Class<?> userParamsClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.parameters.UserParameters");

        // Daten fuer Signatur lesen
        FileInputStream dataForSig = new FileInputStream(dataFile);
        String custId = (String) userParams.getClass().getMethod("getPartnerId").invoke(userParams);
        String usrId = (String) userParams.getClass().getMethod("getUserId").invoke(userParams);

        Object signature = sigCreatorClass.getMethod("createA006",
                String.class, signerClass, InputStream.class, String.class, String.class
        ).invoke(null, ebicsVersion, a00Signer, dataForSig, custId, usrId);
        dataForSig.close();

        // Upload erstellen und ausfuehren
        Class<?> uploadClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.main.Upload");
        Class<?> orderSigClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.model.OrderSignature");
        Class<?> persisterClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.model.Persister");
        Class<?> progressClass = Class.forName("de.ppi.fis.travic.ebics.kernel.api.model.ProgressHandler");

        Object upload = uploadClass.getMethod("create",
                serverParamsClass, userParamsClass,
                String.class, String.class,
                orderSigClass, persisterClass, progressClass, signerClass
        ).invoke(null,
                serverParams, userParams,
                orderType, "DZHNN",
                signature, null, null, x00Signer);

        // Daten setzen
        upload.getClass().getMethod("setData", File.class).invoke(upload, dataFile);

        // Senden
        Object result = upload.getClass().getMethod("send").invoke(upload);
        log.info("EBICS upload successful (REAL mode)");
    }

    /**
     * Erzeugt eine realistische SEPA Credit Transfer Testdatei (pain.001.003.03).
     *
     * Die Datei entspricht dem ISO 20022 Format, wie es typischerweise
     * im deutschen Zahlungsverkehr ueber EBICS versendet wird:
     * - pain.001.003.03 (SEPA Credit Transfer Initiation)
     * - Enthaelt Header (GroupHeader), PaymentInformation und CreditTransferTransaction
     * - Realistische Struktur mit IBAN, BIC, Betraegen und Verwendungszweck
     * - Groesse ~3-4 KB (typisch fuer eine Einzelueberweisung)
     *
     * Die Daten sind fiktiv und koennen nicht fuer echte Ueberweisungen verwendet werden.
     */
    File createTestFile() throws IOException {
        File testFile = new File(EBICS_CONFIG_DIR + "/testfile.xml");

        String msgId = "MSG-" + System.currentTimeMillis();
        String pmtInfId = "PMT-" + System.currentTimeMillis();
        String e2eId = "E2E-" + System.currentTimeMillis();
        String creDtTm = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String reqExecDt = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.003.03"
                          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                          xsi:schemaLocation="urn:iso:std:iso:20022:tech:xsd:pain.001.003.03 pain.001.003.03.xsd">
                  <CstmrCdtTrfInitn>
                    <GrpHdr>
                      <MsgId>%s</MsgId>
                      <CreDtTm>%s</CreDtTm>
                      <NbOfTxs>3</NbOfTxs>
                      <CtrlSum>15750.00</CtrlSum>
                      <InitgPty>
                        <Nm>Benchmark GmbH</Nm>
                        <Id>
                          <OrgId>
                            <Othr>
                              <Id>DE98ZZZ09999999999</Id>
                              <SchmeNm>
                                <Cd>CUST</Cd>
                              </SchmeNm>
                            </Othr>
                          </OrgId>
                        </Id>
                      </InitgPty>
                    </GrpHdr>
                    <PmtInf>
                      <PmtInfId>%s</PmtInfId>
                      <PmtMtd>TRF</PmtMtd>
                      <BtchBookg>true</BtchBookg>
                      <NbOfTxs>3</NbOfTxs>
                      <CtrlSum>15750.00</CtrlSum>
                      <PmtTpInf>
                        <InstrPrty>NORM</InstrPrty>
                        <SvcLvl>
                          <Cd>SEPA</Cd>
                        </SvcLvl>
                      </PmtTpInf>
                      <ReqdExctnDt>%s</ReqdExctnDt>
                      <Dbtr>
                        <Nm>Benchmark GmbH</Nm>
                        <PstlAdr>
                          <Ctry>DE</Ctry>
                          <AdrLine>Teststrasse 42</AdrLine>
                          <AdrLine>60311 Frankfurt am Main</AdrLine>
                        </PstlAdr>
                      </Dbtr>
                      <DbtrAcct>
                        <Id>
                          <IBAN>DE89370400440532013000</IBAN>
                        </Id>
                        <Ccy>EUR</Ccy>
                      </DbtrAcct>
                      <DbtrAgt>
                        <FinInstnId>
                          <BIC>COBADEFFXXX</BIC>
                        </FinInstnId>
                      </DbtrAgt>
                      <ChrgBr>SLEV</ChrgBr>
                      <CdtTrfTxInf>
                        <PmtId>
                          <EndToEndId>%s-001</EndToEndId>
                        </PmtId>
                        <Amt>
                          <InstdAmt Ccy="EUR">5250.00</InstdAmt>
                        </Amt>
                        <CdtrAgt>
                          <FinInstnId>
                            <BIC>DEUTDEDBFRA</BIC>
                          </FinInstnId>
                        </CdtrAgt>
                        <Cdtr>
                          <Nm>Lieferant Alpha GmbH</Nm>
                          <PstlAdr>
                            <Ctry>DE</Ctry>
                            <AdrLine>Industrieweg 7</AdrLine>
                            <AdrLine>80331 Muenchen</AdrLine>
                          </PstlAdr>
                        </Cdtr>
                        <CdtrAcct>
                          <Id>
                            <IBAN>DE27100777770209299700</IBAN>
                          </Id>
                        </CdtrAcct>
                        <RmtInf>
                          <Ustrd>Rechnung RE-2026-00142 vom 01.03.2026</Ustrd>
                        </RmtInf>
                      </CdtTrfTxInf>
                      <CdtTrfTxInf>
                        <PmtId>
                          <EndToEndId>%s-002</EndToEndId>
                        </PmtId>
                        <Amt>
                          <InstdAmt Ccy="EUR">8500.00</InstdAmt>
                        </Amt>
                        <CdtrAgt>
                          <FinInstnId>
                            <BIC>HYVEDEMM430</BIC>
                          </FinInstnId>
                        </CdtrAgt>
                        <Cdtr>
                          <Nm>Dienstleister Beta AG</Nm>
                          <PstlAdr>
                            <Ctry>DE</Ctry>
                            <AdrLine>Handelsplatz 15</AdrLine>
                            <AdrLine>50667 Koeln</AdrLine>
                          </PstlAdr>
                        </Cdtr>
                        <CdtrAcct>
                          <Id>
                            <IBAN>DE62370501980006000123</IBAN>
                          </Id>
                        </CdtrAcct>
                        <RmtInf>
                          <Ustrd>Wartungsvertrag WV-2026-Q1 Maerz 2026</Ustrd>
                        </RmtInf>
                      </CdtTrfTxInf>
                      <CdtTrfTxInf>
                        <PmtId>
                          <EndToEndId>%s-003</EndToEndId>
                        </PmtId>
                        <Amt>
                          <InstdAmt Ccy="EUR">2000.00</InstdAmt>
                        </Amt>
                        <CdtrAgt>
                          <FinInstnId>
                            <BIC>GENODEF1M06</BIC>
                          </FinInstnId>
                        </CdtrAgt>
                        <Cdtr>
                          <Nm>Berater Gamma Consulting</Nm>
                          <PstlAdr>
                            <Ctry>DE</Ctry>
                            <AdrLine>Beraterring 3</AdrLine>
                            <AdrLine>70173 Stuttgart</AdrLine>
                          </PstlAdr>
                        </Cdtr>
                        <CdtrAcct>
                          <Id>
                            <IBAN>DE91600501017402051588</IBAN>
                          </Id>
                        </CdtrAcct>
                        <RmtInf>
                          <Ustrd>Beratungsleistung Februar 2026 Projekt TFL4</Ustrd>
                        </RmtInf>
                      </CdtTrfTxInf>
                    </PmtInf>
                  </CstmrCdtTrfInitn>
                </Document>
                """.formatted(msgId, creDtTm, pmtInfId, reqExecDt, e2eId, e2eId, e2eId);

        Files.writeString(testFile.toPath(), xml, StandardCharsets.UTF_8);
        log.info("Created realistic SEPA test file (pain.001.003.03): {} ({} bytes)",
                testFile.getAbsolutePath(), testFile.length());
        return testFile;
    }

    private void simulateEkOperation() throws InterruptedException {
        Thread.sleep(50);
        byte[] buffer = new byte[8192];
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] = (byte) (i % 256);
        }
    }

    // ==================== Config Utilities ====================

    private Properties readConfig(String path) throws IOException {
        Properties props = new Properties();
        File file = new File(path);
        if (!file.exists()) {
            log.warn("Config file not found: {}, using environment variables", path);
            // Fallback: Environment-Variablen
            props.setProperty("ebicsKernelLicense", env("EBICS_LICENSE", ""));
            props.setProperty("ebicsUrl", env("EBICS_URL", ""));
            props.setProperty("ebicsHostId", env("EBICS_HOST_ID", ""));
            props.setProperty("customerId", env("EBICS_CUSTOMER_ID", ""));
            props.setProperty("userId", env("EBICS_USER_ID", ""));
            return props;
        }

        // Zeile-fuer-Zeile lesen (wie Config.java im EK - kein Properties.load wegen Backslash)
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    if (!value.isEmpty()) {
                        props.setProperty(key, value);
                    }
                }
            }
        }

        // Environment-Variablen ueberschreiben Config-Datei-Werte (z.B. fuer Docker-Networking)
        applyEnvOverride(props, "EBICS_URL", "ebicsUrl");
        applyEnvOverride(props, "EBICS_LICENSE", "ebicsKernelLicense");
        applyEnvOverride(props, "EBICS_HOST_ID", "ebicsHostId");
        applyEnvOverride(props, "EBICS_CUSTOMER_ID", "customerId");
        applyEnvOverride(props, "EBICS_USER_ID", "userId");

        return props;
    }

    private void applyEnvOverride(Properties props, String envVar, String propKey) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            String old = props.getProperty(propKey, "");
            props.setProperty(propKey, value);
            if (!value.equals(old)) {
                log.info("ENV override: {} -> {} (was: {})", envVar, propKey,
                        old.isEmpty() ? "(empty)" : old);
            }
        }
    }

    private String resolveKeyFile(String keyFile) {
        if (keyFile == null || keyFile.isEmpty()) return keyFile;
        File f = new File(keyFile);
        if (f.isAbsolute()) return keyFile;
        // Relativ zu EBICS_CONFIG_DIR aufloesen
        File resolved = new File(EBICS_CONFIG_DIR, keyFile);
        if (resolved.exists()) return resolved.getAbsolutePath();
        // Fallback: wie angegeben
        return keyFile;
    }

    private byte[] loadPkcs12(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            throw new FileNotFoundException("PKCS#12 key file not found: " + path +
                    " (absolute=" + file.getAbsolutePath() + ", cwd=" + System.getProperty("user.dir") + ")");
        }
        return Files.readAllBytes(file.toPath());
    }

    private String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
