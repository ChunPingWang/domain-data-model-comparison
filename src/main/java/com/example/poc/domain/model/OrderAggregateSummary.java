package com.example.poc.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 聚合報表查詢結果 — 用於場景 L (Aggregate Report)
 *
 * @param totalOrders  訂單總數
 * @param totalAmount  所有訂單的金額總和
 * @param averageAmount 平均訂單金額
 * @param countByStatus 按狀態分組的訂單數量
 */
public record OrderAggregateSummary(
        long totalOrders,
        BigDecimal totalAmount,
        BigDecimal averageAmount,
        Map<String, Long> countByStatus
) {
}
