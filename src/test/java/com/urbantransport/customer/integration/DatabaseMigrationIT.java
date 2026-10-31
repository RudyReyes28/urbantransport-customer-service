package com.urbantransport.customer.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationIT extends AbstractCustomerIT {

    @Test
    @DisplayName("Flyway aplicó las migraciones y existe el Consumidor Final")
    void migrationsAndGenericCustomer() {
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")).isGreaterThanOrEqualTo(3);
        assertThat(count("SELECT count(*) FROM customers WHERE id = ? AND nit = 'CF' AND is_generic = TRUE",
                GENERIC_CUSTOMER_ID)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ clientes

    @Test
    @DisplayName("el NIT solo admite dígitos y mayúsculas, y es único")
    void nitFormatAndUniqueness() {
        assertViolation(() -> customer("abc", null, null, false));
        assertViolation(() -> customer("AB-1", null, null, false));
        customer("12345678", null, null, false);
        assertViolation(() -> customer("12345678", null, null, false));
    }

    @Test
    @DisplayName("DPI y user_id son únicos solo cuando tienen valor (índices parciales)")
    void partialUniqueIndexes() {
        customer("100", null, null, false);
        customer("101", null, null, false);                 // varios clientes sin DPI ni cuenta: permitido
        customer("102", dpi(1), null, false);
        assertViolation(() -> customer("103", dpi(1), null, false));

        UUID userId = UUID.randomUUID();
        customer("104", null, userId, false);
        assertViolation(() -> customer("105", null, userId, false));
    }

    @Test
    @DisplayName("solo puede haber un cliente genérico y no puede tener cuenta ni DPI")
    void genericCustomerRules() {
        assertViolation(() -> customer("GEN", null, null, true));          // ya existe CF
        jdbc.update("DELETE FROM customers WHERE is_generic = TRUE");
        assertViolation(() -> customer("GEN", null, UUID.randomUUID(), true));
        assertViolation(() -> customer("GEN", dpi(2), null, true));
        assertThatCode(() -> customer("CF", null, null, true)).doesNotThrowAnyException();
        // Se restaura el estado de la migración para el resto de la suite
        jdbc.update("DELETE FROM customers WHERE is_generic = TRUE");
        jdbc.update("INSERT INTO customers (id, nit, full_name, is_generic) VALUES (?, 'CF', 'Consumidor Final', TRUE)",
                GENERIC_CUSTOMER_ID);
    }

    // ------------------------------------------------------------------ cartera y movimientos

    @Test
    @DisplayName("el saldo no puede ser negativo y la cartera exige un cliente existente")
    void walletConstraints() {
        UUID id = UUID.randomUUID();
        customer("200", null, null, false, id);
        assertViolation(() -> jdbc.update("INSERT INTO wallets (customer_id, balance) VALUES (?, -0.01)", id));
        assertViolation(() -> jdbc.update("INSERT INTO wallets (customer_id, balance) VALUES (?, 0)", UUID.randomUUID()));
        jdbc.update("INSERT INTO wallets (customer_id, balance) VALUES (?, 10)", id);
        assertViolation(() -> jdbc.update("UPDATE wallets SET balance = balance - 11 WHERE customer_id = ?", id));
    }

    @Test
    @DisplayName("movimientos: tipo válido, monto positivo y una sola operación por (referencia, tipo)")
    void walletTransactionConstraints() {
        UUID id = UUID.randomUUID();
        customer("300", null, null, false, id);
        jdbc.update("INSERT INTO wallets (customer_id) VALUES (?)", id);
        UUID ticket = UUID.randomUUID();

        assertViolation(() -> transaction(id, "OTRO", "5", null, null));
        assertViolation(() -> transaction(id, "DEBIT", "0", "TICKET", ticket));
        assertViolation(() -> transaction(id, "DEBIT", "-1", "TICKET", ticket));

        transaction(id, "DEBIT", "5", "TICKET", ticket);
        assertViolation(() -> transaction(id, "DEBIT", "5", "TICKET", ticket));     // mismo pago dos veces
        assertThatCode(() -> transaction(id, "REFUND", "5", "TICKET", ticket)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("las recargas sin referencia (NULL) no chocan entre sí")
    void rechargesWithoutReferenceCoexist() {
        UUID id = UUID.randomUUID();
        customer("400", null, null, false, id);
        jdbc.update("INSERT INTO wallets (customer_id) VALUES (?)", id);

        transaction(id, "RECHARGE", "10", null, null);
        transaction(id, "RECHARGE", "10", null, null);

        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE wallet_id = ?", id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private void customer(String nit, String dpi, UUID userId, boolean generic) {
        customer(nit, dpi, userId, generic, UUID.randomUUID());
    }

    private void customer(String nit, String dpi, UUID userId, boolean generic, UUID id) {
        jdbc.update("INSERT INTO customers (id, nit, dpi, full_name, user_id, is_generic) VALUES (?, ?, ?, 'Nombre', ?, ?)",
                id, nit, dpi, userId, generic);
    }

    private void transaction(UUID walletId, String type, String amount, String referenceType, UUID referenceId) {
        jdbc.update("""
                INSERT INTO wallet_transactions (id, wallet_id, type, amount, balance_after, payment_date,
                                                 reference_type, reference_id)
                VALUES (?, ?, ?, ?, ?, CURRENT_DATE, ?, ?)""",
                UUID.randomUUID(), walletId, type, new BigDecimal(amount), new BigDecimal("1"), referenceType, referenceId);
    }

    private static void assertViolation(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataIntegrityViolationException.class);
    }
}