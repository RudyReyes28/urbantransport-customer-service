package com.urbantransport.customer.repository;

import com.urbantransport.customer.domain.TransactionType;
import com.urbantransport.customer.domain.WalletTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {

    Page<WalletTransactionEntity> findByWalletId(UUID walletId, Pageable pageable);

    boolean existsByReferenceTypeAndReferenceIdAndType(String referenceType, UUID referenceId, TransactionType type);
}