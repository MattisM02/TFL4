package de.mattis.jvmoptimdemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class DemoController {

    // Viele kleine Objekte + JSON: gut für Compact Object Headers
    @GetMapping("/json")
    public List<UserDto> json() {
        int n = 200_000; // später ggf. erhöhen/verringern
        List<UserDto> users = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            users.add(new UserDto("user" + i, i, i % 2 == 0));
        }
        return users;
    }

    // Kurzlebige Objekte erzeugen
    @GetMapping("/alloc")
    public String alloc() {
        int rounds = 200;
        int perRound = 50_000;
        long sum = 0;

        for (int r = 0; r < rounds; r++) {
            List<byte[]> trash = new ArrayList<>(perRound);
            for (int i = 0; i < perRound; i++) {
                byte[] b = new byte[128]; // kleine Arrays
                b[0] = (byte) i;
                trash.add(b);
                sum += b[0];
            }
        }
        return "ok " + sum;
    }

    public record UserDto(String name, int age, boolean active) {}
}
