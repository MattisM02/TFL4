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
        SpringApplication.run(ResourcenOptimierungTfl4Application.class, args);
    }

}
