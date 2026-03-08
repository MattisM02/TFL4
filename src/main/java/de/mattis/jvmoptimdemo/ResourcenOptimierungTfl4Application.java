package de.mattis.jvmoptimdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt der Spring-Boot-Anwendung für das Projekt
 *
 * Diese Klasse markiert das Projekt als Spring-Boot-Anwendung
 * und startet beim Programmstart den Spring Application Context.
 *
 * Beim Start werden unter anderem:
 * - alle @RestController-Klassen erkannt,
 * - die HTTP-Endpunkte registriert,
 * - der eingebettete Webserver gestartet.
 *
 * Diese Klasse enthält keine Business- oder Benchmark-Logik.
 * Sie ist ausschließlich für das Bootstrapping der Anwendung zuständig.
 */
@SpringBootApplication
public class ResourcenOptimierungTfl4Application {

    public static void main(String[] args) {
        // GraalVM Native Image: Xerces kann resource:-URLs nicht intern oeffnen.
        // NativeSchemaFactory wrapt die Standard-SchemaFactory und setzt einen
        // LSResourceResolver, der resource:-URLs auf ClassLoader.getResourceAsStream()
        // abbildet. Nur noetig im Native Image, schadet aber auch nicht auf JVM.
        System.setProperty(
                "javax.xml.validation.SchemaFactory:http://www.w3.org/2001/XMLSchema",
                "de.mattis.jvmoptimdemo.NativeSchemaFactory");

        SpringApplication.run(ResourcenOptimierungTfl4Application.class, args);
    }

}
