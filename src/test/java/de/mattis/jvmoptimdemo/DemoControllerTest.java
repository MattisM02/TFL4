package de.mattis.jvmoptimdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc-Tests fuer DemoController: /json und /alloc Endpunkte.
 */
@WebMvcTest(DemoController.class)
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ==================== /json ====================

    @Test
    void json_defaultN_returnsArray() throws Exception {
        // Default n=200000 is too large for a unit test, use small n
        mockMvc.perform(get("/json").param("n", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("user0"))
                .andExpect(jsonPath("$[0].age").value(0))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].name").value("user1"))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test
    void json_nEquals1_returnsSingleElement() throws Exception {
        mockMvc.perform(get("/json").param("n", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("user0"));
    }

    @Test
    void json_nEquals0_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/json").param("n", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void json_responseIsJsonContentType() throws Exception {
        mockMvc.perform(get("/json").param("n", "1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    // ==================== /alloc ====================

    @Test
    void alloc_returnsOkWithSum() throws Exception {
        // Use small n to keep test fast. n must be >= chunkSize (50000) for at least 1 round.
        // With n=50000: rounds=1, sum will be computed
        mockMvc.perform(get("/alloc").param("n", "50000"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("ok ")));
    }

    @Test
    void alloc_smallN_returnsOkZero() throws Exception {
        // n < chunkSize (50000): rounds=0, no allocations, sum=0
        mockMvc.perform(get("/alloc").param("n", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok 0"));
    }

    @Test
    void alloc_responseIsTextPlain() throws Exception {
        mockMvc.perform(get("/alloc").param("n", "100"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }
}
