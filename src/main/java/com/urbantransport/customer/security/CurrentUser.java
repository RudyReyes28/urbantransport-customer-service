package com.urbantransport.customer.security;

import com.urbantransport.customer.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public final class CurrentUser {

    private CurrentUser() {
    }

    /** El customerId viaja firmado en el token; un token sin ese claim no pertenece a un cliente. */
    public static UUID customerId(Jwt jwt) {
        String value = jwt.getClaimAsString("customerId");
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El token no pertenece a un cliente");
        }
        return UUID.fromString(value);
    }
}