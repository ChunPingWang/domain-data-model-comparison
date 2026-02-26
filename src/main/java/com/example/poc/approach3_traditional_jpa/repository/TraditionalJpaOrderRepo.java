package com.example.poc.approach3_traditional_jpa.repository;

import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TraditionalJpaOrderRepo extends JpaRepository<TraditionalJpaOrder, UUID> {

    /** 場景 M: 批次狀態更新 */
    @Modifying
    @Query("UPDATE TraditionalJpaOrder o SET o.status = :to, o.updatedAt = CURRENT_TIMESTAMP WHERE o.status = :from")
    int bulkUpdateStatus(@Param("from") String from, @Param("to") String to);

    /** 場景 K: 分頁 — Spring Data 的 findAll(Pageable) 已內建，不需額外定義 */

    /** 場景 L: 聚合報表 — count */
    @Query("SELECT COUNT(o) FROM TraditionalJpaOrder o")
    long countOrders();

    /** 場景 L: 聚合報表 — sum */
    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM TraditionalJpaOrder o")
    java.math.BigDecimal sumTotalAmount();

    /** 場景 L: 聚合報表 — groupBy status */
    @Query("SELECT o.status, COUNT(o) FROM TraditionalJpaOrder o GROUP BY o.status")
    List<Object[]> countByStatus();
}
