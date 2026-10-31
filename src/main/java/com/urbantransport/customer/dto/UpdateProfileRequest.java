package com.urbantransport.customer.dto;

import jakarta.validation.constraints.*;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 150) String fullName,
        @NotBlank @Size(max = 20) String nit,
        @NotBlank @Pattern(regexp = "^\\d{13}$", message = "el DPI debe tener 13 dígitos") String dpi,
        @NotBlank @Pattern(regexp = "^\\d{8}$", message = "el teléfono debe tener 8 dígitos") String phone,
        @NotBlank @Size(max = 255) String address) {
}