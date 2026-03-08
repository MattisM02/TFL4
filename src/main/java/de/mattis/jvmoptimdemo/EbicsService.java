package de.mattis.jvmoptimdemo;

import de.ppi.fis.travic.ebics.common.crypt.EbicsRsaPrivateKey;
import de.ppi.fis.travic.ebics.common.crypt.PEMConverter;
import de.ppi.fis.travic.ebics.kernel.api.crypt.Decrypter;
import de.ppi.fis.travic.ebics.kernel.api.crypt.Signer;
import de.ppi.fis.travic.ebics.kernel.api.main.*;
import de.ppi.fis.travic.ebics.kernel.api.model.*;
import de.ppi.fis.travic.ebics.kernel.api.parameters.ServerParameters;
import de.ppi.fis.travic.ebics.kernel.api.parameters.UserParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EBICS-Service: Kapselt die gesamte Interaktion mit dem EBICS Kernel (EK 4.0.9).
 *
 * <p>Verantwortlichkeiten:
 * <ul>
 *   <li>Lazy-Initialisierung: Lizenz setzen, Schluessel laden, HPB abrufen</li>
 *   <li>EBICS-Uploads ausfuehren</li>
 *   <li>Verbindungstest (Schritt-fuer-Schritt Validierung)</li>
 *   <li>Diagnostik bei Init-Fehlern</li>
 * </ul>
 *
 * <p>Thread-Sicherheit: Die Initialisierung verwendet double-checked locking.
 * Nach erfolgreicher Initialisierung sind alle EK-Objekte (ServerParameters,
 * UserParameters, Signer, Decrypter) immutable und koennen parallel genutzt werden.</p>
 */
@Service
public class EbicsService {

    private static final Logger log = LoggerFactory.getLogger(EbicsService.class);

    private final EbicsConfig config;
    private final SepaTestFileGenerator testFileGenerator;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // EK-State — wird bei Initialisierung einmalig gesetzt
    private volatile ServerParameters serverParams;
    private volatile UserParameters userParams;
    private volatile Signer x00Signer;
    private volatile Signer a00Signer;
    private volatile Decrypter e00Decrypter;

    private volatile String initError;

    public EbicsService(EbicsConfig config, SepaTestFileGenerator testFileGenerator) {
        this.config = config;
        this.testFileGenerator = testFileGenerator;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public boolean isInitialized() { return initialized.get(); }
    public String getInitError()   { return initError; }

    /**
     * Stellt sicher, dass der EK initialisiert ist.
     * Thread-safe durch double-checked locking.
     */
    public void ensureInitialized() throws Exception {
        if (initialized.get()) return;

        synchronized (this) {
            if (initialized.get()) return;

            try {
                initializeEk();
                log.info("EK initialized successfully");
            } catch (Exception e) {
                initError = buildInitErrorMessage(e);
                log.error("EK initialization FAILED — detailed diagnostics follow");
                logInitDiagnostics(e);
                throw e;
            }
            initialized.set(true);
        }
    }

    /**
     * Fuehrt einen einzelnen EBICS-Upload durch.
     * Initialisiert den EK bei Bedarf automatisch.
     */
    public void performUpload() throws Exception {
        ensureInitialized();

        log.debug("Performing EBICS upload");

        File dataFile = resolveOrCreateUploadFile();

        // OrderSignature erstellen (A006 = RSA-Signatur nach EBICS-Standard)
        try (FileInputStream dataForSig = new FileInputStream(dataFile)) {
            OrderSignature signature = OrderSignatureCreator.createA006(
                    config.getEbicsVersion(), a00Signer, dataForSig,
                    userParams.getPartnerId(), userParams.getUserId());

            Upload upload = Upload.create(
                    serverParams, userParams,
                    config.getOrderType(), "DZHNN",
                    signature, null, null, x00Signer);

            upload.setData(dataFile);
            Result result = upload.send();

            if (!result.isSuccess()) {
                throw new RuntimeException("EBICS upload failed: technical="
                        + result.getTechnicalReturnCode()
                        + ", bank=" + result.getBankReturnCode());
            }
            log.info("EBICS upload successful");
        }
    }

    /**
     * Fuehrt einen Schritt-fuer-Schritt Verbindungstest durch.
     *
     * <p>Prueft nacheinander: Config, Schluessel, Lizenz, HPB, Test-Upload.
     * Jeder Schritt wird einzeln gemeldet, damit Fehler exakt lokalisierbar sind.</p>
     *
     * @return Map mit Testergebnis und detaillierten Schritten
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> steps = new LinkedHashMap<>();
        long startTime = System.nanoTime();

        try {
            // Schritt 1: Config laden
            validateConfig(steps);

            // Schritt 2: Schluessel pruefen
            validateKeys(steps);

            // Schritt 3: Initialisierung (Lizenz + HPB)
            ensureInitialized();
            steps.put("ekInitialized", true);

            // Schritt 4: Test-Upload
            performUpload();
            steps.put("testUploadSuccess", true);

            result.put("status", "ok");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Connection test failed", e);
        }

        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        result.put("steps", steps);
        result.put("durationMs", durationMs);
        return result;
    }

    // ------------------------------------------------------------------
    // Initialisierung
    // ------------------------------------------------------------------

    /**
     * Fuehrt die vollstaendige EK-Initialisierung durch:
     * 1. Lizenz setzen
     * 2. Sprache setzen
     * 3. PKCS#12-Schluessel laden und Signer/Decrypter erstellen
     * 4. TLS-Zertifikat pruefen (bei verifyTls=false manuell vertrauen)
     * 5. HPB: Bank-Schluessel abrufen
     * 6. Finale ServerParameters mit Bank-Schluesseln erstellen
     */
    private void initializeEk() throws Exception {
        log.info("Initializing EBICS Kernel from config: {}", EbicsConfig.CONFIG_FILE);
        logConfigSummary();

        // 1. Lizenz setzen
        Configuration.setLicense(config.getLicense());
        log.info("License set successfully");

        // 2. Sprache setzen
        Configuration.setLanguage(Locale.ENGLISH);

        // 3. Schluessel laden
        loadKeys();

        // 4. TLS-Check
        String[] tlsCerts = resolveTlsCertificates();

        // 5. HPB — Bank-Schluessel abrufen
        HPBResult hpbResult = performHpb(tlsCerts);

        // 6. Finale ServerParameters mit Bank-Schluesseln
        serverParams = buildFinalServerParams(tlsCerts, hpbResult);

        log.info("EBICS Kernel fully initialized and ready for uploads");
    }

    private void logConfigSummary() {
        log.info("EK config: url={}, hostId={}, customerId={}, userId={}, version={}, orderType={}",
                config.getUrl(), config.getHostId(), config.getCustomerId(),
                config.getUserId(), config.getEbicsVersion(), config.getOrderType());
        log.info("EK key files: A00={} (exists={}), E00={} (exists={}), X00={} (exists={})",
                config.getKeyFileA00(), new File(config.getKeyFileA00()).exists(),
                config.getKeyFileE00(), new File(config.getKeyFileE00()).exists(),
                config.getKeyFileX00(), new File(config.getKeyFileX00()).exists());
    }

    /**
     * Laedt PKCS#12-Schluessel und erstellt Signer/Decrypter.
     * A006 = Elektronische Unterschrift (EU), X002 = Authentifizierung,
     * E002 = Verschluesselung.
     */
    private void loadKeys() throws Exception {
        byte[] a00Pkcs12 = config.loadPkcs12(config.getKeyFileA00());
        byte[] e00Pkcs12 = config.loadPkcs12(config.getKeyFileE00());
        byte[] x00Pkcs12 = config.loadPkcs12(config.getKeyFileX00());

        log.info("Loaded PKCS#12 keys: A00={}bytes, E00={}bytes, X00={}bytes",
                a00Pkcs12.length, e00Pkcs12.length, x00Pkcs12.length);

        String keyPassword = config.getKeyPassword();

        EbicsRsaPrivateKey a00PrivKey = CryptoUtility.getPrivateKeyFromPKCS12(a00Pkcs12, keyPassword);
        EbicsRsaPrivateKey x00PrivKey = CryptoUtility.getPrivateKeyFromPKCS12(x00Pkcs12, keyPassword);
        EbicsRsaPrivateKey e00PrivKey = CryptoUtility.getPrivateKeyFromPKCS12(e00Pkcs12, keyPassword);

        a00Signer = CryptoUtility.createA006Signer(a00PrivKey.getPKCS8KeyBytes());
        x00Signer = CryptoUtility.createX002Signer(x00PrivKey.getPKCS8KeyBytes());
        e00Decrypter = CryptoUtility.createE002Decrypter(e00PrivKey.getPKCS8KeyBytes());

        log.info("Created A006 signer, X002 signer, E002 decrypter");

        // UserParameters hier erstellen, da sie die gleichen Credentials verwenden
        userParams = new UserParameters(config.getUserId(), config.getCustomerId(), "0000");
    }

    /**
     * Prueft das TLS-Zertifikat des EBICS-Servers.
     *
     * <p>Bei {@code verifyTls=false} wird das Zertifikat manuell abgerufen
     * und als vertrauenswuerdig hinterlegt. Das ist in Testumgebungen
     * (z.B. lokaler TravicLink mit Self-Signed-Cert) noetig.</p>
     *
     * @return PEM-codierte TLS-Zertifikate, oder {@code null} wenn TLS verifiziert
     */
    private String[] resolveTlsCertificates() throws Exception {
        if (config.isVerifyTls()) return null;

        // ServerParameters nur fuer den TLS-Check (H000 = minimaler Handshake)
        ServerParameters certCheckParams = new ServerParameters(
                ServerParameters.EBICS_VERSION_H000, config.getUrl(), false, null,
                config.getHostId(), null, null,
                null, 0, null, null, true);

        Certificate cert = Certificate.create(certCheckParams);
        try {
            cert.verify();
            return null; // JVM vertraut dem Zertifikat bereits
        } catch (Exception e) {
            // Zertifikat nicht im JVM-Truststore — manuell vertrauen
            X509Certificate[] chain = cert.getCertificateChain();
            String pem = PEMConverter.CERTIFICATE_CONVERTER.encode(chain[0].getEncoded());
            log.info("TLS certificate not trusted by JVM, using manual trust");
            return new String[]{pem};
        }
    }

    /**
     * Ruft per HPB die Bank-Schluessel ab.
     * HPB = EBICS-Verwaltungsauftrag "Host Parameter Block".
     */
    private HPBResult performHpb(String[] tlsCerts) throws Exception {
        log.info("Calling HPB to obtain bank keys...");

        ServerParameters hpbServerParams = new ServerParameters(
                config.getEbicsVersion(), config.getUrl(),
                TlsVersion.AUTOMATIC, true, tlsCerts,
                config.getHostId(), null, null,
                null, 0, null, null,
                null, null, true,
                null, null, null);

        HPB hpb = HPB.create(hpbServerParams, userParams, x00Signer, e00Decrypter);
        HPBResult result = hpb.send();

        log.info("HPB successful, obtained bank keys");
        return result;
    }

    /**
     * Erstellt die finalen ServerParameters inkl. Bank-Schluessel.
     * Diese werden fuer alle nachfolgenden Uploads verwendet.
     */
    private ServerParameters buildFinalServerParams(String[] tlsCerts, HPBResult hpbResult) {
        byte[] bankAuthKey = hpbResult.getAuthenticationKey();
        byte[] bankEncKey = hpbResult.getEncryptionKey();
        String[] authCerts = hpbResult.getAuthenticationCertificates();
        String[] encCerts = hpbResult.getEncryptionCertificates();
        String bankAuthCert = (authCerts != null && authCerts.length > 0) ? authCerts[0] : null;
        String bankEncCert = (encCerts != null && encCerts.length > 0) ? encCerts[0] : null;

        return new ServerParameters(
                config.getEbicsVersion(), config.getUrl(),
                TlsVersion.AUTOMATIC, true, tlsCerts,
                config.getHostId(), bankAuthKey, bankEncKey,
                null, 0, null, null,
                null, null, true,
                null, bankEncCert, bankAuthCert);
    }

    // ------------------------------------------------------------------
    // Upload-Hilfsmethoden
    // ------------------------------------------------------------------

    /**
     * Gibt die Upload-Datei zurueck. Falls sie nicht existiert,
     * wird eine SEPA-Testdatei erzeugt.
     */
    private File resolveOrCreateUploadFile() throws Exception {
        File dataFile = new File(config.getUploadFilePath());
        if (!dataFile.exists()) {
            dataFile = testFileGenerator.generate(
                    config.getConfigDir() + "/testfile.xml");
        }
        return dataFile;
    }

    // ------------------------------------------------------------------
    // Verbindungstest — Einzelschritte
    // ------------------------------------------------------------------

    private void validateConfig(Map<String, Object> steps) {
        Properties props = config.getProperties();
        boolean hasUrl = props.getProperty("ebicsUrl") != null
                && !props.getProperty("ebicsUrl").isEmpty();
        boolean hasHost = props.getProperty("ebicsHostId") != null
                && !props.getProperty("ebicsHostId").isEmpty();
        steps.put("configLoaded", hasUrl && hasHost);
        steps.put("ebicsUrl", config.getUrl());
        steps.put("ebicsHostId", config.getHostId());
    }

    private void validateKeys(Map<String, Object> steps) {
        steps.put("keyFileA00Exists", new File(config.getKeyFileA00()).exists());
        steps.put("keyFileE00Exists", new File(config.getKeyFileE00()).exists());
        steps.put("keyFileX00Exists", new File(config.getKeyFileX00()).exists());
    }

    // ------------------------------------------------------------------
    // Diagnostik bei Init-Fehlern
    // ------------------------------------------------------------------

    private String buildInitErrorMessage(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
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
    private void logInitDiagnostics(Exception e) {
        log.error("=== EK INIT FAILURE DIAGNOSTICS ===");
        log.error("Exception: {} — {}", e.getClass().getName(), e.getMessage());
        if (e.getCause() != null) {
            log.error("Root cause: {} — {}",
                    e.getCause().getClass().getName(), e.getCause().getMessage());
        }

        logConfigDiagnostics();
        logKeyFileDiagnostics();
        logUploadFileDiagnostics();
        logDirectoryContents();

        log.error("=== END DIAGNOSTICS ===");
    }

    private void logConfigDiagnostics() {
        File configFile = new File(EbicsConfig.CONFIG_FILE);
        log.error("Config file: {} (exists={}, readable={}, size={})",
                EbicsConfig.CONFIG_FILE, configFile.exists(), configFile.canRead(),
                configFile.exists() ? configFile.length() : "N/A");

        try {
            Properties props = config.getProperties();
            log.error("Config values:");
            log.error("  ebicsUrl = {}", props.getProperty("ebicsUrl", "(not set)"));
            log.error("  ebicsHostId = {}", props.getProperty("ebicsHostId", "(not set)"));
            log.error("  customerId = {}", props.getProperty("customerId", "(not set)"));
            log.error("  userId = {}", props.getProperty("userId", "(not set)"));
            log.error("  ebicsVersion = {}", props.getProperty("ebicsVersion", "(not set)"));
            log.error("  orderType = {}", props.getProperty("orderType", "(not set)"));
            log.error("  ebicsKernelLicense = {}",
                    EbicsConfig.maskSensitive(props.getProperty("ebicsKernelLicense")));
            log.error("  keyFileA00Password = {}",
                    EbicsConfig.maskSensitive(props.getProperty("keyFileA00Password")));
            log.error("  verifyTls = {}", props.getProperty("verifyTls", "(not set)"));
            log.error("  verifyBankKeys = {}", props.getProperty("verifyBankKeys", "(not set)"));
        } catch (Exception ex) {
            log.error("Could not read config for diagnostics: {}", ex.getMessage());
        }
    }

    private void logKeyFileDiagnostics() {
        logSingleKeyFile("keyFileA00", config.getKeyFileA00());
        logSingleKeyFile("keyFileE00", config.getKeyFileE00());
        logSingleKeyFile("keyFileX00", config.getKeyFileX00());
    }

    private void logSingleKeyFile(String label, String resolvedPath) {
        File f = new File(resolvedPath);
        log.error("  {} = {} (exists={}, size={})",
                label, resolvedPath, f.exists(),
                f.exists() ? f.length() : "N/A");
    }

    private void logUploadFileDiagnostics() {
        String testFile = config.getConfigDir() + "/testfile.xml";
        File tf = new File(testFile);
        log.error("Test file: {} (exists={}, size={})",
                testFile, tf.exists(), tf.exists() ? tf.length() : "N/A");
    }

    private void logDirectoryContents() {
        File ebicsDir = new File(config.getConfigDir());
        if (ebicsDir.exists() && ebicsDir.isDirectory()) {
            String[] files = ebicsDir.list();
            log.error("EBICS directory contents: {}",
                    files != null ? Arrays.toString(files) : "(empty or error)");
        } else {
            log.error("EBICS directory {} does not exist or is not a directory",
                    config.getConfigDir());
        }
    }
}
