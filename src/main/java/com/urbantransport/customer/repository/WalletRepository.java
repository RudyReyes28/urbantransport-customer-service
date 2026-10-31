package com.urbantransport.customer.repository;

import com.urbantransport.customer.domain.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {

    /** Crea la cartera si no existe; es seguro aunque dos peticiones lo intenten a la vez. */
    @Modifying
    @Query(value = "INSERT INTO wallets (customer_id) VALUES (:customerId) ON CONFLICT (customer_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("customerId") UUID customerId);

    /** Descuenta solo si alcanza el saldo; devuelve el saldo nuevo, o vacío si no alcanzó. */
    @Query(value = "UPDATE wallets SET balance = balance - :amount, version = version + 1, updated_at = now() "
            + "WHERE customer_id = :customerId AND balance >= :amount RETURNING balance", nativeQuery = true)
    Optional<BigDecimal> debitIfEnough(@Param("customerId") UUID customerId, @Param("amount") BigDecimal amount);

    /** Acredita y devuelve el saldo nuevo. */
    @Query(value = "UPDATE wallets SET balance = balance + :amount, version = version + 1, updated_at = now() "
            + "WHERE customer_id = :customerId RETURNING balance", nativeQuery = true)
    Optional<BigDecimal> credit(@Param("customerId") UUID customerId, @Param("amount") BigDecimal amount);
}