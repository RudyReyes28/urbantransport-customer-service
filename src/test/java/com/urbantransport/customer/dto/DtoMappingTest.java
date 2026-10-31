package com.urbantransport.customer.dto;

import com.urbantransport.customer.domain.CustomerEntity;
import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.domain.WalletEntity;
import com.urbantransport.customer.domain.WalletTransactionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DtoMappingTest {

    @Test
    @DisplayName("CustomerResponse copia todos los campos del cliente")
    void customerResponse() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();
        CustomerEntity entity = CustomerEntity.builder().id(id).nit("12345678").dpi("1234567890123")
                .fullName("Ana Pérez").phone("55512345").address("Zona 1").email("ana@it.test")
                .userId(userId).createdAt(createdAt).build();

        CustomerResponse response = CustomerResponse.fromEntity(entity);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getNit()).isEqualTo("12345678");
        assertThat(response.getDpi()).isEqualTo("1234567890123");
        assertThat(response.getFullName()).isEqualTo("Ana Pérez");
        assertThat(response.getPhone()).isEqualTo("55512345");
        assertThat(response.getAddress()).isEqualTo("Zona 1");
        assertThat(response.getEmail()).isEqualTo("ana@it.test");
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("CustomerSummaryResponse: hasAccount depende de userId; generic viene de la entidad")
    void customerSummary() {
        CustomerEntity withAccount = CustomerEntity.builder().id(UUID.randomUUID()).nit("1").fullName("A")
                .userId(UUID.randomUUID()).build();
        CustomerEntity counterOnly = CustomerEntity.builder().id(UUID.randomUUID()).nit("2").fullName("B").build();
        CustomerEntity generic = CustomerEntity.builder().id(UUID.randomUUID()).nit("CF").fullName("Consumidor Final")
                .generic(true).build();

        assertThat(CustomerSummaryResponse.fromEntity(withAccount).isHasAccount()).isTrue();
        assertThat(CustomerSummaryResponse.fromEntity(counterOnly).isHasAccount()).isFalse();
        assertThat(CustomerSummaryResponse.fromEntity(counterOnly).isGeneric()).isFalse();
        assertThat(CustomerSummaryResponse.fromEntity(generic).isGeneric()).isTrue();
    }

    @Test
    @DisplayName("WalletResponse y WalletTransactionResponse")
    void walletMappings() {
        UUID customerId = UUID.randomUUID();
        WalletEntity wallet = WalletEntity.builder().customerId(customerId).balance(new BigDecimal("50.25")).build();
        UUID txId = UUID.randomUUID();
        UUID ref = UUID.randomUUID();
        WalletTransactionEntity tx = WalletTransactionEntity.builder().id(txId).walletId(customerId)
                .type(TransactionType.DEBIT).amount(new BigDecimal("10.00")).balanceAfter(new BigDecimal("40.25"))
                .paymentDate(LocalDate.of(2026, 10, 27)).referenceType("TICKET").referenceId(ref).build();

        WalletResponse walletResponse = WalletResponse.fromEntity(wallet);
        WalletTransactionResponse txResponse = WalletTransactionResponse.fromEntity(tx);

        assertThat(walletResponse.getCustomerId()).isEqualTo(customerId);
        assertThat(walletResponse.getBalance()).isEqualByComparingTo("50.25");
        assertThat(txResponse.getId()).isEqualTo(txId);
        assertThat(txResponse.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(txResponse.getAmount()).isEqualByComparingTo("10.00");
        assertThat(txResponse.getBalanceAfter()).isEqualByComparingTo("40.25");
        assertThat(txResponse.getPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(txResponse.getReferenceType()).isEqualTo("TICKET");
        assertThat(txResponse.getReferenceId()).isEqualTo(ref);
    }

    @Test
    @DisplayName("PageResponse.of traslada contenido y datos de paginación")
    void pageResponse() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.of(page);

        assertThat(response.getContent()).containsExactly("a", "b");
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(2);
        assertThat(response.getTotalElements()).isEqualTo(5);
        assertThat(response.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("valores por defecto de las entidades: cliente no genérico y cartera en cero")
    void entityDefaults() {
        assertThat(CustomerEntity.builder().build().isGeneric()).isFalse();
        assertThat(WalletEntity.builder().build().getBalance()).isEqualByComparingTo("0");
    }
}