package de.mattis.jvmoptimdemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class DemoController {

    // Payload-heavy: viele kleine Objekte + JSON
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

    // Alloc-heavy: viele kurzlebige Objekte, kleine Antwort
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

    public record UserDto(String name, int age, boolean active) {}
}
