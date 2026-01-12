package de.mattis.jvmoptimdemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring MVC REST-Controller, der gezielt zwei verschiedene Workloads bereitstellt, um JVM-Optimierungen
 * (z. B. CompressedOops, Compact Object Headers, GC-Verhalten) vergleichbar zu messen.
 *
 * <p>Wichtig: Diese Klasse wird nicht “direkt” im Code aufgerufen. Spring Boot erkennt sie zur Laufzeit über
 * {@link RestController} und mappt die Methoden anhand der {@link GetMapping}-Annotationen auf HTTP-Routen.</p>
 *
 * <p><b>Workloads</b></p>
 * <ul>
 *   <li><b>/json</b> (payload-heavy): Erzeugt viele kleine DTO-Objekte und gibt sie als JSON zurück.
 *       Das misst E2E: Objekt-Erzeugung + Serialisierung + Netzwerk + Client liest Response.</li>
 *   <li><b>/alloc</b> (alloc-heavy): Erzeugt sehr viele kurzlebige Objekte/Arrays, Antwort bleibt klein ("ok ...").
 *       Das isoliert stärker serverseitige Allocation/GC/Objektlayout-Effekte.</li>
 * </ul>
 *
 * <p>Die Parameter {@code n} erlauben, die “Stärke” der Workload zu skalieren, ohne neue Endpoints zu bauen.</p>
 */

@RestController
public class DemoController {

    /**
     * Payload-heavy Workload: Erzeugt {@code n} {@link UserDto} und gibt sie als JSON-Array zurück.
     *
     * <p>Parameter:</p>
     * <ul>
     *   <li>{@code n}: Anzahl der zu erzeugenden UserDto-Objekte (Default: 200000).</li>
     * </ul>
     *
     * @param n Anzahl der DTOs (Payload-Größe)
     * @return Liste von {@link UserDto}, wird von Spring/Jackson als JSON serialisiert
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
     * Alloc-heavy Workload: Erzeugt viele kurzlebige Byte-Arrays (Heap-Druck/GC),
     * liefert aber nur eine kleine Text-Antwort.
     *
     * <p>Hinweis: Es wird in Chunks gearbeitet, damit keine riesige Liste mit {@code n} Elementen entsteht.</p>
     *
     * <p>Parameter:</p>
     * <ul>
     *   <li>{@code n}: Zielanzahl der zu erzeugenden Arrays (Default: 10000000).
     *       Intern wird das über {@code rounds = n / chunkSize} realisiert (Rest wird ignoriert).</li>
     * </ul>
     *
     * @param n Zielanzahl der Arrays (Allocation-Menge)
     * @return "ok &lt;sum&gt;" (sum verhindert komplette Wegoptimierung durch JIT)
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
     * DTO für den {@code /json}-Endpoint.
     *
     * @param name Benutzername (z. B. "user123")
     * @param age Alter (hier gleich dem Index)
     * @param active Aktiv-Flag (hier alternierend)
     */
    public record UserDto(String name, int age, boolean active) {}
}
