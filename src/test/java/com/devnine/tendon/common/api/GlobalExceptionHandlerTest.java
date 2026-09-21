package com.devnine.tendon.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class GlobalExceptionHandlerTest {
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestController()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void shouldReturn400whenValidationExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/validation")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath(".message").value("Invalid input"));
    }

    @Test
    void shouldReturn404whenNotFoundExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath(".message").value("User not found"));
    }

    @Test
    void shouldReturn409whenConflictExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath(".message").value("User already exists"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/validation")
        String validation() {
            throw new ValidationException("Invalid input");
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new NotFoundException("User not found");
        }

        @GetMapping("/test/conflict")
        String conflict() {
            throw new ConflictException("User already exists");
        }
    }
}
