package com.urbantransport.customer.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los tópicos de boletos y rentas tienen 1 partición, así que sus mensajes se procesan en orden. Los tests usan un
 * mensaje "centinela" enviado al final: cuando se procesa, todo lo anterior ya pasó por el listener y se puede
 * afirmar lo que NO ocurrió sin esperas fijas.
 */
class PaymentFlowIT extends AbstractCustomerIT {

    private Account ana;

    @BeforeEach
    void fundedCustomer() throws Exception {
        ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        fundWallet(ana.customerId(), "100.00");
    }

    // ------------------------------------------------------------------ pagos

    @Test
    @DisplayName("PaymentRequested: debita la cartera, registra el evento y publica PaymentCompleted con el cliente como clave")
    void paymentIsDebitedAndConfirmed() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, ana.customerId(), null, "30.00"));

        awaitProcessed(eventId);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("70.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE wallet_id = ? AND type = 'DEBIT' "
                        + "AND reference_type = 'TICKET' AND reference_id = ? AND payment_date = DATE '2026-10-27'",
                ana.customerId(), ticket)).isEqualTo(1);
        ConsumerRecord<String, String> completed = paymentEvent(ana.customerId().toString(), "PaymentCompleted", ticket);
        assertThat((String) read(completed.value(), "$.payload.referenceType")).isEqualTo("TICKET");
        assertThat((Object) readOrNull(completed.value(), "$.payload.reason")).isNull();
        assertThat((Integer) read(completed.value(), "$.version")).isEqualTo(1);
    }

    @Test
    @DisplayName("el cliente puede identificarse solo por NIT (con guion): se debita igual")
    void customerResolvedByNit() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, null, "1234567-8", "25.00"));

        awaitProcessed(eventId);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("75.00");
        paymentEvent(ana.customerId().toString(), "PaymentCompleted", ticket);
    }

    @Test
    @DisplayName("las rentas llegan por su propio tópico y se procesan igual")
    void rentalTopicIsConsumed() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID rental = UUID.randomUUID();
        Map<String, Object> payload = paymentPayload(rental, ana.customerId(), null, "40.00");
        payload.put("referenceType", "RENTAL");

        publish(RENTAL_TOPIC, rental.toString(), event("PaymentRequested", eventId, payload));

        awaitProcessed(eventId);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("60.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE reference_type = 'RENTAL' AND reference_id = ?",
                rental)).isEqualTo(1);
    }

    @Test
    @DisplayName("saldo insuficiente: PaymentFailed(INSUFFICIENT_FUNDS), el saldo no cambia y no hay movimiento")
    void insufficientFunds() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, ana.customerId(), null, "500.00"));

        awaitProcessed(eventId);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("100.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'DEBIT'")).isZero();
        ConsumerRecord<String, String> failed = paymentEvent(ana.customerId().toString(), "PaymentFailed", ticket);
        assertThat((String) read(failed.value(), "$.payload.reason")).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("cliente inexistente: PaymentFailed(CUSTOMER_NOT_FOUND) con la referencia como clave")
    void customerNotFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, null, "99999999", "10.00"));

        awaitProcessed(eventId);
        ConsumerRecord<String, String> failed = paymentEvent(ticket.toString(), "PaymentFailed", ticket);
        assertThat((String) read(failed.value(), "$.payload.reason")).isEqualTo("CUSTOMER_NOT_FOUND");
    }

    @Test
    @DisplayName("Consumidor Final o cliente sin cuenta: PaymentFailed(CUSTOMER_WITHOUT_ACCOUNT)")
    void customerWithoutAccount() throws Exception {
        UUID ticket = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, null, "CF", "10.00"));

        awaitProcessed(eventId);
        ConsumerRecord<String, String> failed = paymentEvent(GENERIC_CUSTOMER_ID.toString(), "PaymentFailed", ticket);
        assertThat((String) read(failed.value(), "$.payload.reason")).isEqualTo("CUSTOMER_WITHOUT_ACCOUNT");
    }

    @Test
    @DisplayName("monto inválido: PaymentFailed(INVALID_AMOUNT) sin tocar la cartera")
    void invalidAmount() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(eventId, ticket, ana.customerId(), null, "0"));

        awaitProcessed(eventId);
        ConsumerRecord<String, String> failed = paymentEvent(ana.customerId().toString(), "PaymentFailed", ticket);
        assertThat((String) read(failed.value(), "$.payload.reason")).isEqualTo("INVALID_AMOUNT");
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("100.00");
    }

    // ------------------------------------------------------------------ idempotencia

    @Test
    @DisplayName("el mismo evento entregado dos veces (mismo eventId) se aplica una sola vez")
    void replayedEventIsIgnored() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();
        String message = paymentRequested(eventId, ticket, ana.customerId(), null, "30.00");
        publish(TICKET_TOPIC, ticket.toString(), message);
        publish(TICKET_TOPIC, ticket.toString(), message);
        UUID sentinelEvent = UUID.randomUUID();
        UUID sentinelTicket = UUID.randomUUID();
        publish(TICKET_TOPIC, sentinelTicket.toString(),
                paymentRequested(sentinelEvent, sentinelTicket, ana.customerId(), null, "10.00"));

        awaitProcessed(sentinelEvent);

        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("60.00");   // 100 - 30 - 10
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'DEBIT'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("la misma referencia con eventIds distintos (reintento del emisor) se debita una sola vez")
    void sameReferenceWithDifferentEventIdsIsDebitedOnce() throws Exception {
        UUID ticket = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(first, ticket, ana.customerId(), null, "30.00"));
        publish(TICKET_TOPIC, ticket.toString(), paymentRequested(second, ticket, ana.customerId(), null, "30.00"));

        awaitProcessed(second);

        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("70.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE reference_id = ? AND type = 'DEBIT'", ticket))
                .isEqualTo(1);
        // Ambos intentos se confirman: el emisor recibe PaymentCompleted también en el reintento
        assertThat(awaitRecords(PAYMENT_TOPIC, r -> "PaymentCompleted".equals(readOrNull(r.value(), "$.eventType"))
                && ticket.toString().equals(readOrNull(r.value(), "$.payload.referenceId")), 2, Duration.ofSeconds(30)))
                .hasSize(2);
    }

    // ------------------------------------------------------------------ reembolsos

    @Test
    @DisplayName("RefundRequested: acredita el monto, registra REFUND con el boleto y publica RefundCompleted")
    void refund() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID ticket = UUID.randomUUID();

        publish(TICKET_TOPIC, ticket.toString(), refundRequested(eventId, ticket, ana.customerId(), "12.345"));

        awaitProcessed(eventId);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("112.35");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'REFUND' AND reference_type = 'TICKET' "
                + "AND reference_id = ?", ticket)).isEqualTo(1);
        ConsumerRecord<String, String> completed = awaitRecords(PAYMENT_TOPIC,
                r -> "RefundCompleted".equals(readOrNull(r.value(), "$.eventType"))
                        && ticket.toString().equals(readOrNull(r.value(), "$.payload.ticketId")),
                1, Duration.ofSeconds(30)).get(0);
        assertThat(completed.key()).isEqualTo(ana.customerId().toString());
        assertThat(decimal(completed.value(), "$.payload.amount")).isEqualByComparingTo("12.35");
    }

    @Test
    @DisplayName("un reembolso repetido para el mismo boleto (otro eventId) se acredita una sola vez")
    void refundIsIdempotentPerTicket() throws Exception {
        UUID ticket = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        publish(TICKET_TOPIC, ticket.toString(), refundRequested(first, ticket, ana.customerId(), "20.00"));
        publish(TICKET_TOPIC, ticket.toString(), refundRequested(second, ticket, ana.customerId(), "20.00"));

        awaitProcessed(second);

        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("120.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'REFUND' AND reference_id = ?", ticket))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ errores: DLT

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("mensajes imposibles de procesar (JSON roto, payload sin referencia) terminan en .DLT y no bloquean los siguientes (~35 s)")
    void poisonMessagesGoToTheDlt() throws Exception {
        String brokenJson = "esto-no-es-json-" + UUID.randomUUID();
        UUID withoutReference = UUID.randomUUID();
        Map<String, Object> payload = paymentPayload(UUID.randomUUID(), ana.customerId(), null, "10.00");
        payload.remove("referenceId");
        String missingReference = event("PaymentRequested", withoutReference, payload);
        publish(TICKET_TOPIC, "poison-1", brokenJson);
        publish(TICKET_TOPIC, "poison-2", missingReference);
        UUID sentinelEvent = UUID.randomUUID();
        UUID sentinelTicket = UUID.randomUUID();
        publish(TICKET_TOPIC, sentinelTicket.toString(),
                paymentRequested(sentinelEvent, sentinelTicket, ana.customerId(), null, "10.00"));

        List<ConsumerRecord<String, String>> dead = awaitRecords(TICKET_TOPIC + ".DLT",
                r -> brokenJson.equals(r.value()) || missingReference.equals(r.value()), 2, Duration.ofSeconds(100));

        assertThat(dead).hasSize(2);
        awaitProcessed(sentinelEvent);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("90.00");
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", withoutReference)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private ConsumerRecord<String, String> paymentEvent(String key, String eventType, UUID reference) {
        return awaitRecords(PAYMENT_TOPIC, r -> key.equals(r.key())
                        && eventType.equals(readOrNull(r.value(), "$.eventType"))
                        && reference.toString().equals(readOrNull(r.value(), "$.payload.referenceId")),
                1, Duration.ofSeconds(30)).get(0);
    }

    private String paymentRequested(UUID eventId, UUID reference, UUID customerId, String nit, String amount) {
        return event("PaymentRequested", eventId, paymentPayload(reference, customerId, nit, amount));
    }

    private Map<String, Object> paymentPayload(UUID reference, UUID customerId, String nit, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("referenceType", "TICKET");
        payload.put("referenceId", reference);
        payload.put("customerId", customerId);
        payload.put("customerNit", nit);
        payload.put("amount", new BigDecimal(amount));
        payload.put("paymentDate", "2026-10-27");
        return payload;
    }

    private String refundRequested(UUID eventId, UUID ticket, UUID customerId, String amount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ticketId", ticket);
        payload.put("customerId", customerId);
        payload.put("amount", new BigDecimal(amount));
        return event("RefundRequested", eventId, payload);
    }
}