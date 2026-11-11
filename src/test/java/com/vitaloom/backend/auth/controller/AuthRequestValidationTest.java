package com.vitaloom.backend.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vitaloom.backend.auth.controller.exceptionhandler.AuthExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.vitaloom.backend.auth.dto.request.LoginRequest;
import com.vitaloom.backend.auth.dto.request.RegisterRequest;
import jakarta.validation.Validation;

class AuthRequestValidationTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(null))
                .setControllerAdvice(new AuthExceptionHandler())
                .build();
    }

    @Test
    void malformedJsonUsesValidationErrorEnvelope() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":{"code":"VALIDATION_ERROR","message":"The request payload is invalid."}}
                        """));
    }

    @Test
    void missingLoginPasswordIsRejectedAsValidationError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":{"code":"VALIDATION_ERROR","message":"The request payload is invalid."}}
                        """));
    }

    @Test
    void blankRefreshTokenIsRejectedAsValidationError() throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":{"code":"VALIDATION_ERROR","message":"The request payload is invalid."}}
                        """));
    }

    @Test
    void blankLoginPasswordPassesRequestValidation() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        LoginRequest request = new LoginRequest(" User@Example.com ", "");
        assertEquals("User@Example.com", request.email());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void registrationRequiresEightPasswordCharactersWithoutComplexityRules() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertTrue(validator.validate(new RegisterRequest("user@example.com", "1234567")).size() > 0);
        assertTrue(validator.validate(new RegisterRequest("user@example.com", "12345678")).isEmpty());
    }
}
