package com.vitaloom.backend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(@NotBlank @Email String email, @NotNull String password) {
    public LoginRequest { if (email != null) email = email.strip(); }
}
