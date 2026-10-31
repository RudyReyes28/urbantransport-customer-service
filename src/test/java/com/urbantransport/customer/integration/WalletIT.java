package com.urbantransport.customer.integration;

import com.urbantransport.customer.service.TransactionOutcome;
import com.urbantransport.customer.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletIT extends AbstractCustomerIT {

    @Autowired
    private WalletService walletService;

    // ------------------------------------------------------------------ consulta

    @Test
    @DisplayName("GET /wallet: devuelve el saldo y crea la cartera si faltaba (idempotente)")
    void getWalletCreatesItIfMissing() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        jdbc.update("DELETE FROM wallets WHERE customer_id = ?", ana.customerId());
        String token = IDENTITY.customerToken(ana.customerId());

        String first = responseBody(send(get("/wallet"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(ana.customerId().toString())));
        send(get("/wallet"), token, null).andExpect(status().isOk());

        assertThat(decimal(first, "$.balance")).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM wallets WHERE customer_id = ?", ana.customerId())).isEqualTo(1);
    }

    @Test
    @DisplayName("el Consumidor Final y los clientes sin cuenta no tienen cartera: 409")
    void walletRequiresAnAccount() throws Exception {
        send(get("/wallet"), IDENTITY.customerToken(GENERIC_CUSTOMER_ID), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Solo los clientes con cuenta tienen cartera"));

        String counter = responseBody(send(post("/customers"), staffToken(), Map.of("nit", "12345678", "fullName", "Ana"))
                .andExpect(status().isCreated()));
        send(post("/wallet/recharge"), IDENTITY.customerToken(UUID.fromString(read(counter, "$.id"))),
                Map.of("amount", 10)).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ recarga

    @Test
    @DisplayName("recarga: suma al saldo, registra el movimiento con saldo posterior y emite WalletRecharged")
    void recharge() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        String token = IDENTITY.customerToken(ana.customerId());

        send(post("/wallet/recharge"), token, Map.of("amount", new BigDecimal("100.00"), "paymentDate", "2026-10-01"))
                .andExpect(status().isOk());
        String second = responseBody(send(post("/wallet/recharge"), token, Map.of("amount", new BigDecimal("50.25")))
                .andExpect(status().isOk()));

        assertThat(decimal(second, "$.balance")).isEqualByComparingTo("150.25");
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("150.25");
        assertThat(jdbc.queryForObject("SELECT payment_date FROM wallet_transactions WHERE amount = 100.00",
                java.sql.Date.class).toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(jdbc.queryForObject("SELECT payment_date FROM wallet_transactions WHERE amount = 50.25",
                java.sql.Date.class).toLocalDate()).isEqualTo(LocalDate.now());
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE type = 'RECHARGE' AND reference_id IS NULL"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT balance_after FROM wallet_transactions WHERE amount = 50.25",
                BigDecimal.class)).isEqualByComparingTo("150.25");
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE event_type = 'WalletRecharged' ORDER BY created_at LIMIT 1", String.class);
        assertThat((String) read(payload, "$.payload.customerId")).isEqualTo(ana.customerId().toString());
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'WalletRecharged'")).isEqualTo(2);
    }

    @Test
    @DisplayName("el monto máximo permitido (100000.00) se acepta")
    void maximumRecharge() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");

        send(post("/wallet/recharge"), IDENTITY.customerToken(ana.customerId()),
                Map.of("amount", new BigDecimal("100000.00"))).andExpect(status().isOk());

        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("recargas inválidas (monto fuera de rango, 3 decimales, sin monto, fecha futura) -> 400 sin efectos")
    void invalidRecharges() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        String token = IDENTITY.customerToken(ana.customerId());

        for (String amount : new String[]{"0", "0.00", "-5", "100000.01", "1.234"}) {
            send(post("/wallet/recharge"), token, Map.of("amount", new BigDecimal(amount)))
                    .andExpect(status().isBadRequest());
        }
        send(post("/wallet/recharge"), token, Map.of()).andExpect(status().isBadRequest());
        send(post("/wallet/recharge"), token, Map.of("amount", 10, "paymentDate", LocalDate.now().plusDays(2).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("paymentDate: la fecha de pago no puede ser futura"));

        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM wallet_transactions")).isZero();
    }

    // ------------------------------------------------------------------ movimientos

    @Test
    @DisplayName("movimientos: más recientes primero, paginados, con límites, y cada cliente ve solo los suyos")
    void transactions() throws Exception {
        Account ana = registerCustomer("11111111", dpi(1), "ana@it.test");
        Account beto = registerCustomer("22222222", dpi(2), "beto@it.test");
        for (String amount : new String[]{"10.00", "20.00", "30.00"}) {
            fundWallet(ana.customerId(), amount);
            Thread.sleep(15);   // created_at distinto entre movimientos
        }
        String token = IDENTITY.customerToken(ana.customerId());

        send(get("/wallet/transactions").param("size", "2"), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].amount").value(30.0))
                .andExpect(jsonPath("$.content[0].type").value("RECHARGE"))
                .andExpect(jsonPath("$.content[1].amount").value(20.0));
        send(get("/wallet/transactions").param("size", "2").param("page", "1"), token, null)
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].amount").value(10.0));
        send(get("/wallet/transactions").param("size", "1000").param("page", "-4"), token, null)
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0));
        send(get("/wallet/transactions"), IDENTITY.customerToken(beto.customerId()), null)
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------ concurrencia sobre el dinero

    @Test
    @DisplayName("diez recargas simultáneas: el saldo final es exactamente la suma y hay un movimiento por recarga")
    void concurrentRecharges() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        String token = IDENTITY.customerToken(ana.customerId());
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> send(post("/wallet/recharge"), token, Map.of("amount", new BigDecimal("10.00")))
                    .andReturn().getResponse().getStatus());
        }

        List<Object> results = runInParallel(tasks);

        assertThat(results).containsOnly(200);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("100.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE wallet_id = ? AND type = 'RECHARGE'",
                ana.customerId())).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'WalletRecharged'")).isEqualTo(10);
    }

    @Test
    @DisplayName("diez débitos simultáneos de 30 sobre un saldo de 100: se aplican exactamente 3 y el saldo no se vuelve negativo")
    void concurrentDebitsNeverOverdraw() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        fundWallet(ana.customerId(), "100.00");
        List<Callable<TransactionOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> walletService.debit(ana.customerId(), new BigDecimal("30.00"), "TEST", UUID.randomUUID(),
                    LocalDate.now()));
        }

        List<Object> results = runInParallel(tasks);

        assertThat(results.stream().filter(TransactionOutcome.APPLIED::equals).count()).isEqualTo(3);
        assertThat(results.stream().filter(TransactionOutcome.INSUFFICIENT_FUNDS::equals).count()).isEqualTo(7);
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("10.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE wallet_id = ? AND type = 'DEBIT'",
                ana.customerId())).isEqualTo(3);
    }

    @Test
    @DisplayName("el mismo pago procesado a la vez desde cinco hilos se descuenta una sola vez")
    void samePaymentIsDebitedOnlyOnce() throws Exception {
        Account ana = registerCustomer("12345678", dpi(1), "ana@it.test");
        fundWallet(ana.customerId(), "100.00");
        UUID ticket = UUID.randomUUID();
        List<Callable<TransactionOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            tasks.add(() -> walletService.debit(ana.customerId(), new BigDecimal("10.00"), "TICKET", ticket, LocalDate.now()));
        }

        List<Object> results = runInParallel(tasks);

        assertThat(results.stream().filter(TransactionOutcome.APPLIED::equals).count()).isEqualTo(1);
        // Los demás: ya aplicado, o chocaron con el índice de idempotencia y se revirtieron (saldo incluido)
        assertThat(results.stream().filter(r -> !TransactionOutcome.APPLIED.equals(r)))
                .allSatisfy(result -> assertThat(result).satisfiesAnyOf(
                        r -> assertThat(r).isEqualTo(TransactionOutcome.ALREADY_APPLIED),
                        r -> assertThat(r).isInstanceOf(DataIntegrityViolationException.class)));
        assertThat(balanceOf(ana.customerId())).isEqualByComparingTo("90.00");
        assertThat(count("SELECT count(*) FROM wallet_transactions WHERE reference_id = ? AND type = 'DEBIT'", ticket))
                .isEqualTo(1);
    }
}