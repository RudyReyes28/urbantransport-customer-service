package com.urbantransport.customer.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static com.urbantransport.customer.support.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    @Test
    @DisplayName("devuelve el customerId firmado en el token")
    void returnsCustomerId() {
        UUID id = UUID.randomUUID();

        assertThat(CurrentUser.customerId(jwt(id.toString()))).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("claim en blanco -> 403")
    void blankClaimIsForbidden(String claim) {
        assertApiError(() -> CurrentUser.customerId(jwt(claim)), HttpStatus.FORBIDDEN,
                "El token no pertenece a un cliente");
    }

    @Test
    @DisplayName("token sin claim customerId (staff o chofer) -> 403")
    void missingClaimIsForbidden() {
        assertApiError(() -> CurrentUser.customerId(jwt(null)), HttpStatus.FORBIDDEN,
                "El token no pertenece a un cliente");
    }

    @Test
    @DisplayName("un customerId que no es UUID lanza IllegalArgumentException (no ocurre con tokens de identity)")
    void malformedClaimIsNotAUuid() {
        assertThatThrownBy(() -> CurrentUser.customerId(jwt("no-es-uuid")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Jwt jwt(String customerIdClaim) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(UUID.randomUUID().toString());
        if (customerIdClaim != null) {
            builder.claim("customerId", customerIdClaim);
        }
        return builder.build();
    }
}