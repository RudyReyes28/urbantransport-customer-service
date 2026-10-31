package com.urbantransport.customer.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerRegistrationIT extends AbstractCustomerIT {
/*
    @Test
    @DisplayName("registro: crea cliente y cartera, enlaza la cuenta y llama a identity con la clave interna y datos normalizados")
    void registersANewCustomer() throws Exception {
        String response = responseBody(send(post("/customers/register"), null,
                registerBody("1234567-8", dpi(1), "  Ana@Mail.COM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nit").value("12345678"))
                .andExpect(jsonPath("$.email").value("ana@mail.com"))
                .andExpect(jsonPath("$.fullName").value("Ana Pérez"))
                .andExpect(jsonPath("$.userId").isNotEmpty()));
        UUID customerId = UUID.fromString(read(response, "$.id"));

        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND user_id IS NOT NULL", customerId)).isEqualTo(1);
        assertThat(balanceOf(customerId)).isEqualByComparingTo("0");
        assertThat(IDENTITY.received()).hasSize(1);
        assertThat(IDENTITY.received().get(0).apiKey()).isEqualTo(API_KEY);
        String sent = IDENTITY.received().get(0).body();
        assertThat((String) read(sent, "$.customerId")).isEqualTo(customerId.toString());
        assertThat((String) read(sent, "$.nit")).isEqualTo("12345678");
        assertThat((String) read(sent, "$.email")).isEqualTo("ana@mail.com");
        assertThat((String) read(sent, "$.password")).isEqualTo("Password123");
    }
*/
    @Test
    @DisplayName("el mismo NIT no se registra dos veces: 409 y sin una segunda llamada a identity")
    void duplicateNit() throws Exception {
        registerCustomer("12345678", dpi(1), "ana@it.test");

        send(post("/customers/register"), null, registerBody("1234-5678", dpi(2), "otra@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Este NIT ya tiene una cuenta. Inicia sesión"));

        assertThat(IDENTITY.received()).hasSize(1);
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isEqualTo(1);
    }

    @Test
    @DisplayName("cliente de ventanilla sin DPI: al registrarse conserva su id, se completa su DPI y se enlaza la cuenta")
    void counterCustomerUpgradesToAccount() throws Exception {
        String created = responseBody(send(post("/customers"), staffToken(),
                Map.of("nit", "12345678", "fullName", "Ana (ventanilla)")).andExpect(status().isCreated()));
        UUID customerId = UUID.fromString(read(created, "$.id"));

        send(post("/customers/register"), null, registerBody("12345678", dpi(1), "ana@it.test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(customerId.toString()))
                .andExpect(jsonPath("$.dpi").value(dpi(1)));

        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND user_id IS NOT NULL AND dpi = ?",
                customerId, dpi(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("DPI que no coincide con el cliente existente, o ya usado por otro NIT -> 409 sin llamar a identity")
    void dpiConflicts() throws Exception {
        send(post("/customers"), staffToken(), Map.of("nit", "11111111", "fullName", "Con DPI", "dpi", dpi(1)))
                .andExpect(status().isCreated());
        send(post("/customers"), staffToken(), Map.of("nit", "22222222", "fullName", "Sin DPI"))
                .andExpect(status().isCreated());

        send(post("/customers/register"), null, registerBody("11111111", dpi(9), "a@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Los datos no coinciden con el cliente registrado para este NIT"));
        send(post("/customers/register"), null, registerBody("22222222", dpi(1), "b@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El DPI ya está registrado con otro NIT"));
        send(post("/customers/register"), null, registerBody("33333333", dpi(1), "c@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El DPI ya está registrado con otro NIT"));

        assertThat(IDENTITY.received()).isEmpty();
    }

    // ------------------------------------------------------------------ compensación cuando identity falla

    @Test
    @DisplayName("correo ya usado en identity: 409 con su mensaje y el cliente recién creado se elimina")
    void identityRejectsTheEmail() throws Exception {
        registerCustomer("11111111", dpi(1), "ana@it.test");

        send(post("/customers/register"), null, registerBody("22222222", dpi(2), "ANA@it.test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya existe un usuario con el correo ana@it.test"));

        assertThat(count("SELECT count(*) FROM customers WHERE nit = '22222222'")).isZero();
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '11111111' AND user_id IS NOT NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("identity caído (500) -> 503, sin restos, y el mismo registro funciona al recuperarse")
    void identityDown() throws Exception {
        IDENTITY.failWith(500);

        send(post("/customers/register"), null, registerBody("12345678", dpi(1), "ana@it.test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El servicio de autenticación no está disponible, intenta más tarde"));
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isZero();
        assertThat(count("SELECT count(*) FROM wallets")).isZero();

        IDENTITY.failWith(0);
        registerCustomer("12345678", dpi(1), "ana@it.test");
    }

    @Test
    @DisplayName("clave interna rechazada por identity (401) -> 502 genérico y sin restos")
    void identityRejectsTheInternalKey() throws Exception {
        IDENTITY.failWith(401);

        send(post("/customers/register"), null, registerBody("12345678", dpi(1), "ana@it.test"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("No se pudo crear la cuenta, intenta más tarde"));
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isZero();
    }

    @Test
    @DisplayName("identity responde más lento que el read-timeout -> 503 y sin restos")
    void identityTimeout() throws Exception {
        IDENTITY.delay(2500);

        send(post("/customers/register"), null, registerBody("12345678", dpi(1), "ana@it.test"))
                .andExpect(status().isServiceUnavailable());
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isZero();
    }

    @Test
    @DisplayName("si identity falla, un cliente de ventanilla que ya existía NO se borra")
    void preexistingCustomerSurvivesIdentityFailure() throws Exception {
        send(post("/customers"), staffToken(), Map.of("nit", "12345678", "fullName", "Ana")).andExpect(status().isCreated());
        IDENTITY.failWith(500);

        send(post("/customers/register"), null, registerBody("12345678", dpi(1), "ana@it.test"))
                .andExpect(status().isServiceUnavailable());

        assertThat(count("SELECT count(*) FROM customers WHERE nit = '12345678'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ validación

    @Test
    @DisplayName("validación de campos y NIT inválido -> 400 y no se toca ni la base ni identity")
    void validation() throws Exception {
        Map<String, Object> invalid = Map.of("email", "no-es-correo", "password", "corta", "fullName", " ",
                "nit", "12345678", "dpi", "123", "phone", "12", "address", "");
        String response = responseBody(send(post("/customers/register"), null, invalid).andExpect(status().isBadRequest()));
        assertThat((String) read(response, "$.message"))
                .contains("email").contains("password").contains("fullName").contains("dpi").contains("phone").contains("address");

        send(post("/customers/register"), null, registerBody("ABC", dpi(1), "ana@it.test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El NIT no tiene un formato válido"));

        assertThat(count("SELECT count(*) FROM customers WHERE is_generic = FALSE")).isZero();
        assertThat(IDENTITY.received()).isEmpty();
    }

    // ------------------------------------------------------------------ concurrencia
/*
    @Test
    @DisplayName("dos registros simultáneos del mismo NIT: a lo sumo uno gana y nunca queda una cuenta huérfana en identity")
    void concurrentRegistrationsNeverLeaveAnOrphanAccount() throws Exception {
        List<Callable<Integer>> tasks = List.of(
                () -> registrationStatus("99999999", "uno@it.test"),
                () -> registrationStatus("99999999", "dos@it.test"));

        List<Object> results = runInParallel(tasks);

        long created = results.stream().filter(result -> Integer.valueOf(201).equals(result)).count();
        assertThat(created).isLessThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '99999999'")).isLessThanOrEqualTo(1);
        // Invariante: cada cuenta creada en identity corresponde a un cliente que sigue existiendo y enlazado
        assertThat(count("SELECT count(*) FROM customers WHERE nit = '99999999' AND user_id IS NOT NULL"))
                .isEqualTo(IDENTITY.accountsCreated());
    }
*/
    private int registrationStatus(String nit, String email) throws Exception {
        return send(post("/customers/register"), null, registerBody(nit, dpi(7), email))
                .andReturn().getResponse().getStatus();
    }
}