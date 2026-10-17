package com.urbantransport.customer.repository;

import com.urbantransport.customer.domain.OutboxEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();

    @Modifying
    @Transactional
    @Query("update OutboxEventEntity e set e.publishedAt = :now where e.id = :id")
    void markPublished(@Param("id") UUID id, @Param("now") LocalDateTime now);
}