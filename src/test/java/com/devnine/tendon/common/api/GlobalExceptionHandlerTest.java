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

    @Test
    void shouldReturn401whenAuthExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/auth")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath(".message").value("Authentication required"));
    }

    @Test
    void shouldReturn403whenNotAuthorizedExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/not-authorized")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath(".message").value("Access denied"));
    }

    @Test
    void shouldReturn429whenRateLimitExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/rate-limit")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath(".message").value("Too many requests"));
    }

    @Test
    void shouldReturn500whenInternalExceptionThrown() throws Exception {
        mockMvc.perform(get("/test/internal")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath(".message").value("Internal server error"));
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

        @GetMapping("/test/auth")
        String auth() {
            throw new AuthException("Authentication required");
        }

        @GetMapping("/test/not-authorized")
        String notAuthorized() {
            throw new NotAuthorizedException("Access denied");
        }

        @GetMapping("/test/rate-limit")
        String rateLimit() {
            throw new RateLimitException("Too many requests");
        }

        @GetMapping("/test/internal")
        String internal() {
            throw new InternalException("Internal server error");
        }
    }
}
