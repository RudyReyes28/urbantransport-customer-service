package com.urbantransport.customer.repository;

import com.urbantransport.customer.domain.CustomerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    Optional<CustomerEntity> findByNit(String nit);

    Optional<CustomerEntity> findByDpi(String dpi);

    Optional<CustomerEntity> findByUserId(UUID userId);

    boolean existsByNit(String nit);

    boolean existsByDpi(String dpi);
}