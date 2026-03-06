package de.mattis.jvmoptimdemo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests fuer SepaTestFileGenerator.
 *
 * Testet die XML-Erzeugung mit dem SEPA pain.001 Template.
 */
class SepaTestFileGeneratorTest {

    @Test
    void generate_createsValidXmlFile(@TempDir Path tempDir) throws IOException {
        SepaTestFileGenerator generator = new SepaTestFileGenerator();
        String outputPath = tempDir.resolve("test-output.xml").toString();

        File result = generator.generate(outputPath);

        assertTrue(result.exists(), "Generated file should exist");
        assertTrue(result.length() > 0, "Generated file should not be empty");

        String content = Files.readString(result.toPath());
        assertTrue(content.startsWith("<?xml"), "Should start with XML declaration");
        assertTrue(content.contains("pain.001.003.03"), "Should contain pain.001 namespace");
    }

    @Test
    void generate_replacesAllPlaceholders(@TempDir Path tempDir) throws IOException {
        SepaTestFileGenerator generator = new SepaTestFileGenerator();
        String outputPath = tempDir.resolve("test-output.xml").toString();

        File result = generator.generate(outputPath);
        String content = Files.readString(result.toPath());

        // Kein Platzhalter darf uebrig sein
        assertFalse(content.contains("${"), "No unresolved placeholders should remain");
    }

    @Test
    void generate_containsUniqueMsgId(@TempDir Path tempDir) throws IOException {
        SepaTestFileGenerator generator = new SepaTestFileGenerator();

        File first = generator.generate(tempDir.resolve("first.xml").toString());
        File second = generator.generate(tempDir.resolve("second.xml").toString());

        String content1 = Files.readString(first.toPath());
        String content2 = Files.readString(second.toPath());

        // MsgId sollte sich zwischen Aufrufen unterscheiden (zeitbasiert)
        String msgId1 = extractBetween(content1, "<MsgId>", "</MsgId>");
        String msgId2 = extractBetween(content2, "<MsgId>", "</MsgId>");
        assertNotEquals(msgId1, msgId2, "Each file should have a unique MsgId");
    }

    @Test
    void generate_containsThreeTransactions(@TempDir Path tempDir) throws IOException {
        SepaTestFileGenerator generator = new SepaTestFileGenerator();
        String outputPath = tempDir.resolve("test-output.xml").toString();

        File result = generator.generate(outputPath);
        String content = Files.readString(result.toPath());

        // 3 CdtTrfTxInf-Bloecke mit EndToEndId -001, -002, -003
        assertTrue(content.contains("-001</EndToEndId>"), "Should contain transaction 001");
        assertTrue(content.contains("-002</EndToEndId>"), "Should contain transaction 002");
        assertTrue(content.contains("-003</EndToEndId>"), "Should contain transaction 003");
    }

    @Test
    void generate_containsExpectedAmounts(@TempDir Path tempDir) throws IOException {
        SepaTestFileGenerator generator = new SepaTestFileGenerator();
        File result = generator.generate(tempDir.resolve("test-output.xml").toString());
        String content = Files.readString(result.toPath());

        assertTrue(content.contains("5250.00"), "Should contain amount 5250.00");
        assertTrue(content.contains("8500.00"), "Should contain amount 8500.00");
        assertTrue(content.contains("2000.00"), "Should contain amount 2000.00");
        assertTrue(content.contains("<CtrlSum>15750.00</CtrlSum>"), "Control sum should be 15750.00");
    }

    private static String extractBetween(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) return "";
        s += start.length();
        int e = text.indexOf(end, s);
        if (e < 0) return "";
        return text.substring(s, e);
    }
}
