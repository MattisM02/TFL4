package de.mattis.jvmoptimdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Erzeugt realistische SEPA Credit Transfer Testdateien (pain.001.003.03).
 *
 * <p>Das XML-Template wird aus {@code classpath:templates/sepa-pain001-template.xml}
 * geladen. Platzhalter ({@code ${msgId}}, {@code ${creDtTm}} etc.) werden durch
 * aktuelle Werte ersetzt, sodass jeder Upload eine eindeutige Nachricht erzeugt.</p>
 *
 * <p>Die erzeugten Daten sind fiktiv und koennen nicht fuer echte Ueberweisungen
 * verwendet werden.</p>
 */
@Component
public class SepaTestFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(SepaTestFileGenerator.class);
    private static final String TEMPLATE_PATH = "templates/sepa-pain001-template.xml";

    /** Monotoner Zaehler fuer eindeutige IDs, auch bei gleichzeitigen Aufrufen. */
    private static final AtomicLong ID_COUNTER = new AtomicLong(System.currentTimeMillis());

    private volatile String templateContent;

    /**
     * Erzeugt eine SEPA-Testdatei am angegebenen Pfad.
     *
     * <p>Falls die Datei bereits existiert, wird sie ueberschrieben.
     * Platzhalter im Template werden durch eindeutige, zeitabhaengige
     * Werte ersetzt.</p>
     *
     * @param outputPath Zielpfad fuer die erzeugte XML-Datei
     * @return die erzeugte Datei
     * @throws IOException wenn das Template nicht geladen oder die Datei nicht geschrieben werden kann
     */
    public File generate(String outputPath) throws IOException {
        String template = loadTemplate();

        String msgId = "MSG-" + ID_COUNTER.incrementAndGet();
        String pmtInfId = "PMT-" + ID_COUNTER.incrementAndGet();
        String e2eId = "E2E-" + ID_COUNTER.incrementAndGet();
        String creDtTm = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String reqExecDt = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        String xml = template
                .replace("${msgId}", msgId)
                .replace("${creDtTm}", creDtTm)
                .replace("${pmtInfId}", pmtInfId)
                .replace("${reqExecDt}", reqExecDt)
                .replace("${e2eId}", e2eId);

        File file = new File(outputPath);
        Files.writeString(file.toPath(), xml, StandardCharsets.UTF_8);

        log.info("Created SEPA test file (pain.001.003.03): {} ({} bytes)",
                file.getAbsolutePath(), file.length());
        return file;
    }

    /** Laedt das Template einmalig aus dem Classpath (lazy, thread-safe). */
    private String loadTemplate() throws IOException {
        if (templateContent == null) {
            synchronized (this) {
                if (templateContent == null) {
                    try (InputStream is = getClass().getClassLoader()
                            .getResourceAsStream(TEMPLATE_PATH)) {
                        if (is == null) {
                            throw new IOException(
                                    "SEPA template not found on classpath: " + TEMPLATE_PATH);
                        }
                        templateContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    log.debug("Loaded SEPA template from classpath ({} chars)",
                            templateContent.length());
                }
            }
        }
        return templateContent;
    }
}
