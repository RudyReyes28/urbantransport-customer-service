package com.urbantransport.customer.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityIT extends AbstractCustomerIT {

    // ------------------------------------------------------------------ rutas públicas y protegidas

    @Test
    @DisplayName("health, OpenAPI y el registro son públicos (el registro llega a la validación, no al 401)")
    void publicEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        send(post("/customers/register"), null, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin token, las rutas protegidas responden 401")
    void anonymousRequestsAreRejected() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get("/customers/me"), put("/customers/me"), get("/customers/by-nit/12345678"), post("/customers"),
                get("/wallet"), post("/wallet/recharge"), get("/wallet/transactions"));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    // ------------------------------------------------------------------ tokens inválidos

    @Test
    @DisplayName("token basura, con algoritmo 'none', firmado con otra llave (mismo kid) o vencido -> 401")
    void invalidTokensAreRejected() throws Exception {
        UUID customerId = UUID.randomUUID();

        send(get("/customers/me"), "no.es.un-jwt", null).andExpect(status().isUnauthorized());
        send(get("/customers/me"), "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.", null).andExpect(status().isUnauthorized());
        send(get("/customers/me"), IDENTITY.foreignToken("CUSTOMER", customerId), null).andExpect(status().isUnauthorized());
        send(get("/customers/me"), IDENTITY.expiredToken("CUSTOMER", customerId), null).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ roles

    @Test
    @DisplayName("un CUSTOMER no accede a las rutas de personal")
    void customerCannotUseStaffEndpoints() throws Exception {
        String customer = IDENTITY.customerToken(UUID.randomUUID());

        send(get("/customers/by-nit/12345678"), customer, null).andExpect(status().isForbidden());
        send(post("/customers"), customer, Map.of("nit", "12345678", "fullName", "Ana")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "BRANCH_ADMIN", "SYSTEM_ADMIN", "DRIVER"})
    @DisplayName("el personal no accede a /customers/me ni a la cartera (son del cliente), sea cual sea su rol")
    void staffCannotUseCustomerEndpoints(String role) throws Exception {
        String token = IDENTITY.token(role, null);

        send(get("/customers/me"), token, null).andExpect(status().isForbidden());
        send(get("/wallet"), token, null).andExpect(status().isForbidden());
        send(post("/wallet/recharge"), token, Map.of("amount", 10)).andExpect(status().isForbidden());
        send(get("/wallet/transactions"), token, null).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "BRANCH_ADMIN", "SYSTEM_ADMIN"})
    @DisplayName("CASHIER, BRANCH_ADMIN y SYSTEM_ADMIN pueden consultar y crear clientes")
    void staffRolesCanManageCustomers(String role) throws Exception {
        String token = IDENTITY.token(role, null);

        send(get("/customers/by-nit/00000000"), token, null)
                .andExpect(status().isNotFound())      // autorizado: llega al servicio, que no lo encuentra
                .andExpect(jsonPath("$.message").value("No existe un cliente con ese NIT"));
        send(post("/customers"), token, Map.of("nit", "7" + role.length(), "fullName", "Cliente " + role))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("un token CUSTOMER sin claim customerId -> 403; con un customerId que ya no existe -> 404")
    void customerClaimRules() throws Exception {
        send(get("/customers/me"), IDENTITY.token("CUSTOMER", null), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("El token no pertenece a un cliente"));
        send(get("/customers/me"), IDENTITY.customerToken(UUID.randomUUID()), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Cliente no encontrado"));
    }

    // ------------------------------------------------------------------ correlation id

    @Test
    @DisplayName("X-Correlation-Id: se genera si no viene y se respeta si viene")
    void correlationId() throws Exception {
        String generated = mockMvc.perform(get("/actuator/health"))
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(generated).isNotBlank();

        mockMvc.perform(get("/actuator/health").header("X-Correlation-Id", "corr-123"))
                .andExpect(header().string("X-Correlation-Id", "corr-123"));
    }
}