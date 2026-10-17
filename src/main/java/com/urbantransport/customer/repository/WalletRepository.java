package com.urbantransport.customer.repository;

import com.urbantransport.customer.domain.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {
}