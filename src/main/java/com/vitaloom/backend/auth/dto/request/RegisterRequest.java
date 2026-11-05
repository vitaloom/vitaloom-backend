package com.vitaloom.backend.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(@NotBlank @Email String email,
                              @NotNull @Size(min = 8) String password) {
    public RegisterRequest { if (email != null) email = email.strip(); }
}
