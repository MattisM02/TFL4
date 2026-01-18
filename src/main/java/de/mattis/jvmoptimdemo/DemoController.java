package de.mattis.jvmoptimdemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring MVC REST-Controller mit zwei bewusst unterschiedlichen Workloads,
 * die für Benchmark-Zwecke genutzt werden.
 *
 * Ziel ist es, JVM-Optimierungen wie Compressed OOPs, Compact Object Headers
 * oder Native Images vergleichbar zu messen.
 *
 * Die Methoden dieser Klasse werden nicht direkt aufgerufen.
 * Spring Boot erkennt den Controller automatisch über die @RestController-
 * Annotation und stellt die Methoden als HTTP-Endpunkte bereit.
 *
 * Enthaltene Workloads:
 * - /json  : payload-lastig (Objekterzeugung + JSON-Serialisierung)
 * - /alloc : allocation-lastig (Heap-Druck und GC-Verhalten)
 *
 * Über den Parameter n kann die Intensität der Workloads skaliert werden.
 */

@RestController
public class DemoController {

    /**
     * Payload-lastiger Workload.
     *
     * Erzeugt n UserDto-Objekte und gibt sie als JSON-Array zurück.
     * Damit werden Objekterzeugung, Objektgraph-Größe und JSON-Serialisierung
     * gemeinsam belastet.
     *
     * Parameter:
     * - n: Anzahl der zu erzeugenden DTOs (Default: 200000)
     *
     * @param n Anzahl der DTOs
     * @return Liste von UserDto, die als JSON serialisiert wird
     */
    @GetMapping("/json")
    public List<UserDto> json(
            @RequestParam(name = "n", defaultValue = "200000") int n
    ) {
        List<UserDto> users = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            users.add(new UserDto("user" + i, i, i % 2 == 0));
        }
        return users;
    }

    /**
     * Allocation-lastiger Workload.
     *
     * Erzeugt sehr viele kurzlebige Byte-Arrays, um Heap-Allokation,
     * Objektlayout und Garbage Collection zu belasten.
     * Die HTTP-Antwort selbst bleibt bewusst klein.
     *
     * Um extrem große Datenstrukturen zu vermeiden, wird intern
     * in festen Chunks gearbeitet.
     *
     * Parameter:
     * - n: Zielanzahl der zu erzeugenden Arrays (Default: 10000000)
     *
     * @param n Zielanzahl der Allokationen
     * @return einfache Textantwort ("ok <sum>")
     */
    @GetMapping("/alloc")
    public String alloc(
            @RequestParam(name = "n", defaultValue = "10000000") int n
    ) {
        long sum = 0;

        // chunking, damit keine riesige Liste entsteht
        int chunkSize = 50_000;
        int rounds = n / chunkSize;

        for (int r = 0; r < rounds; r++) {
            List<byte[]> trash = new ArrayList<>(chunkSize);
            for (int i = 0; i < chunkSize; i++) {
                byte[] b = new byte[128];
                b[0] = (byte) i;
                trash.add(b);
                sum += b[0];
            }
        }
        return "ok " + sum;
    }

    /**
     * Einfaches DTO für den /json-Endpunkt.
     *
     * @param name Benutzername
     * @param age Alter (hier identisch mit dem Index)
     * @param active Aktiv-Flag
     */
    public record UserDto(String name, int age, boolean active) {}
}
