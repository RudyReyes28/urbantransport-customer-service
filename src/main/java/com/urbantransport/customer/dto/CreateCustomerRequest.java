package com.urbantransport.customer.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Alta de un cliente en ventanilla: sin cuenta de acceso. Dpi, teléfono y dirección son opcionales (null, no vacío). */
public record CreateCustomerRequest(
        @NotBlank @Size(max = 20) String nit,
        @NotBlank @Size(max = 150) String fullName,
        @Pattern(regexp = "^\\d{13}$", message = "el DPI debe tener 13 dígitos") String dpi,
        @Pattern(regexp = "^\\d{8}$", message = "el teléfono debe tener 8 dígitos") String phone,
        @Size(max = 255) String address) {
}