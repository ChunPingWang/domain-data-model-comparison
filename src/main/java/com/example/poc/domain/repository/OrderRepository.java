package com.example.poc.domain.repository;

import com.example.poc.domain.model.Order;
import com.example.poc.domain.model.OrderAggregateSummary;
import com.example.poc.domain.model.OrderStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(UUID id);
    List<Order> findAll();
    void deleteAll();

    /** 場景 K: 分頁查詢 — 回傳指定頁的 Orders */
    List<Order> findAllPaged(int page, int size);

    /** 場景 L: 聚合報表 — count / sum / avg / groupBy status */
    OrderAggregateSummary computeAggregateSummary();

    /** 場景 M: 批次狀態更新 — 將指定狀態的所有訂單改為新狀態 */
    int bulkUpdateStatus(OrderStatus from, OrderStatus to);

    /** 場景 N: 跨 Aggregate 查詢 — 找包含特定 productId 的所有訂單 */
    List<Order> findByProductId(String productId);
}
