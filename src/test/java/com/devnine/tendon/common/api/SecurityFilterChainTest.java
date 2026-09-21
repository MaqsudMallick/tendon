package com.devnine.tendon.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({SecurityConfig.class, SecurityFilterChainTest.StubEndpoints.class})
class SecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowHealthEndpointWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectReferencesEndpointWithoutCredentials() throws Exception {
        mockMvc.perform(get("/api/references"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectPrometheusEndpointWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @RestController
    static class StubEndpoints {

        @GetMapping("/actuator/health")
        String health() {
            return "UP";
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }

        @GetMapping("/api/references")
        String references() {
            return "[]";
        }
    }
}