package com.example.poc.approach3_traditional_jpa.repository;

import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TraditionalJpaLineItemRepo extends JpaRepository<TraditionalJpaLineItem, UUID> {

    List<TraditionalJpaLineItem> findByOrderId(UUID orderId);

    void deleteByOrderId(UUID orderId);

    /** 場景 N: 找包含特定 productId 的所有 orderId */
    @Query("SELECT DISTINCT li.orderId FROM TraditionalJpaLineItem li WHERE li.productId = :productId")
    List<UUID> findOrderIdsByProductId(@Param("productId") String productId);
}
