package de.mattis.jvmoptimdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt der Spring-Boot-Anwendung für das Projekt
 * „Ressourcenoptimierung TFL4“.
 *
 * <p>Diese Klasse erfüllt zwei zentrale Aufgaben:</p>
 * <ul>
 *   <li>Sie markiert das Projekt als Spring-Boot-Anwendung
 *       (über {@link SpringBootApplication}).</li>
 *   <li>Sie startet beim Programmstart den Spring Application Context.</li>
 * </ul>
 *
 * <p>Beim Start werden automatisch:</p>
 * <ul>
 *   <li>alle {@code @RestController}-Klassen (z. B. {@code DemoController}) erkannt,</li>
 *   <li>die HTTP-Endpunkte registriert,</li>
 *   <li>und der eingebettete Webserver gestartet.</li>
 * </ul>
 *
 * <p>Diese Klasse enthält keinerlei Business- oder Benchmark-Logik.
 * Sie ist ausschließlich für das Bootstrapping der Anwendung zuständig.</p>
 */
@SpringBootApplication
public class ResourcenOptimierungTfl4Application {

    public static void main(String[] args) {
        SpringApplication.run(ResourcenOptimierungTfl4Application.class, args);
    }

}
