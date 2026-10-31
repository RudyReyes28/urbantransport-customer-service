package com.urbantransport.customer.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerApiIT extends AbstractCustomerIT {

    // ------------------------------------------------------------------ perfil propio

    @Test
    @DisplayName("GET /customers/me devuelve el perfil del cliente del token")
    void myProfile() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");

        send(get("/customers/me"), IDENTITY.customerToken(ana.customerId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ana.customerId().toString()))
                .andExpect(jsonPath("$.nit").value("12345678"))
                .andExpect(jsonPath("$.email").value("ana@it.test"))
                .andExpect(jsonPath("$.userId").value(ana.userId().toString()));
    }

    @Test
    @DisplayName("PUT /customers/me sin cambiar el NIT: actualiza y recorta los datos y NO emite evento")
    void updateWithoutNitChange() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");

        send(put("/customers/me"), IDENTITY.customerToken(ana.customerId()),
                profile("1234567-8", dpi(1), "  Ana Nueva ", "  Zona 10 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ana Nueva"))
                .andExpect(jsonPath("$.address").value("Zona 10"))
                .andExpect(jsonPath("$.phone").value("55598765"));

        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    @Test
    @DisplayName("cambiar el NIT: se guarda y se publica CustomerNitChanged (lo que consume identity), con el correlationId")
    void nitChangeIsPublished() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");

        send(put("/customers/me").header("X-Correlation-Id", "corr-nit-1"), IDENTITY.customerToken(ana.customerId()),
                profile("8765432-1", dpi(1), "Ana Pérez", "Zona 1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value("87654321"));

        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND nit = '87654321'", ana.customerId())).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(count("SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL")).isEqualTo(1));
        String message = awaitRecords(CUSTOMER_TOPIC, r -> ana.customerId().toString().equals(r.key()), 1,
                Duration.ofSeconds(30)).get(0).value();
        assertThat((String) read(message, "$.eventType")).isEqualTo("CustomerNitChanged");
        assertThat((String) read(message, "$.payload.customerId")).isEqualTo(ana.customerId().toString());
        assertThat((String) read(message, "$.payload.nit")).isEqualTo("87654321");
        assertThat((String) read(message, "$.correlationId")).isEqualTo("corr-nit-1");
        assertThat((Integer) read(message, "$.version")).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /customers/me: NIT o DPI de otro cliente -> 409; NIT inválido -> 400; sin cambios guardados")
    void updateConflicts() throws Exception {
        Account ana = registerCustomer("11111111", dpi(1), "ana@it.test");
        registerCustomer("22222222", dpi(2), "beto@it.test");
        String token = IDENTITY.customerToken(ana.customerId());

        send(put("/customers/me"), token, profile("22222222", dpi(1), "Ana", "Zona 1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Ese NIT ya pertenece a otro cliente"));
        send(put("/customers/me"), token, profile("11111111", dpi(2), "Ana", "Zona 1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Ese DPI ya pertenece a otro cliente"));
        send(put("/customers/me"), token, profile("ABC", dpi(1), "Ana", "Zona 1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("El NIT no tiene un formato válido"));

        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND nit = '11111111' AND dpi = ?",
                ana.customerId(), dpi(1))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    // ------------------------------------------------------------------ consulta por NIT (personal)

    @Test
    @DisplayName("buscar por NIT normaliza la entrada e informa si tiene cuenta; el Consumidor Final se identifica como genérico")
    void findByNit() throws Exception {
        registerCustomer("12345678", dpi(1), "ana@it.test");
        send(post("/customers"), staffToken(), Map.of("nit", "87654321", "fullName", "Sin cuenta")).andExpect(status().isCreated());

        send(get("/customers/by-nit/1234-5678"), staffToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nit").value("12345678"))
                .andExpect(jsonPath("$.hasAccount").value(true))
                .andExpect(jsonPath("$.generic").value(false));
        send(get("/customers/by-nit/87654321"), staffToken(), null)
                .andExpect(jsonPath("$.hasAccount").value(false));
        send(get("/customers/by-nit/cf"), staffToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(GENERIC_CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.generic").value(true))
                .andExpect(jsonPath("$.hasAccount").value(false));
        send(get("/customers/by-nit/99999999"), staffToken(), null).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ alta en ventanilla (personal)

    @Test
    @DisplayName("alta en ventanilla: NIT normalizado, datos opcionales, sin cuenta ni cartera")
    void createWithoutAccount() throws Exception {
        send(post("/customers"), staffToken(), Map.of("nit", "1234567-8", "fullName", "  Ana  ", "dpi", dpi(1),
                "phone", "55512345", "address", " Zona 1 "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nit").value("12345678"))
                .andExpect(jsonPath("$.fullName").value("Ana"))
                .andExpect(jsonPath("$.hasAccount").value(false));
        send(post("/customers"), staffToken(), Map.of("nit", "87654321", "fullName", "Solo lo básico"))
                .andExpect(status().isCreated());

        assertThat(count("SELECT count(*) FROM customers WHERE user_id IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM wallets")).isZero();
        assertThat(IDENTITY.received()).isEmpty();
    }

    @Test
    @DisplayName("alta en ventanilla: NIT repetido (incluido CF) o DPI repetido -> 409; NIT inválido -> 400; teléfono inválido -> 400")
    void createRejections() throws Exception {
        send(post("/customers"), staffToken(), Map.of("nit", "12345678", "fullName", "Ana", "dpi", dpi(1)))
                .andExpect(status().isCreated());

        send(post("/customers"), staffToken(), Map.of("nit", "1234-5678", "fullName", "Otra"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Ya existe un cliente con ese NIT"));
        send(post("/customers"), staffToken(), Map.of("nit", "CF", "fullName", "Otro CF"))
                .andExpect(status().isConflict());
        send(post("/customers"), staffToken(), Map.of("nit", "87654321", "fullName", "Otra", "dpi", dpi(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Ese DPI ya está registrado con otro NIT"));
        send(post("/customers"), staffToken(), Map.of("nit", "ABC", "fullName", "Otra"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("El NIT no tiene un formato válido"));
        send(post("/customers"), staffToken(), Map.of("nit", "87654321", "fullName", "Otra", "phone", "123"))
                .andExpect(status().isBadRequest());

        assertThat(count("SELECT count(*) FROM customers WHERE is_generic = FALSE")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> profile(String nit, String dpi, String fullName, String address) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("nit", nit);
        body.put("dpi", dpi);
        body.put("phone", "55598765");
        body.put("address", address);
        return body;
    }
}