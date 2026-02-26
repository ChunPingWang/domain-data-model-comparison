package com.example.poc.approach1_ddd_jpa.repository;

import com.example.poc.approach1_ddd_jpa.entity.JpaOrderEntity;
import com.example.poc.domain.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataJpaOrderRepository extends JpaRepository<JpaOrderEntity, UUID> {

    @Query("SELECT o FROM JpaOrderEntity o LEFT JOIN FETCH o.lineItems WHERE o.id = :id")
    Optional<JpaOrderEntity> findByIdWithLineItems(@Param("id") UUID id);

    /** 場景 K: 分頁 (Spring Data Pageable) */
    Page<JpaOrderEntity> findAll(Pageable pageable);

    /** 場景 M: 批次狀態更新 — 回傳受影響行數 */
    @Modifying
    @Query("UPDATE JpaOrderEntity o SET o.status = :to, o.updatedAt = CURRENT_TIMESTAMP WHERE o.status = :from")
    int bulkUpdateStatus(@Param("from") OrderStatus from, @Param("to") OrderStatus to);

    /** 場景 N: 跨 Aggregate 查詢 — 找包含特定 productId 的 Order IDs */
    @Query("SELECT DISTINCT li.order.id FROM JpaOrderLineItemEntity li WHERE li.productId = :productId")
    List<UUID> findOrderIdsByProductId(@Param("productId") String productId);
}
