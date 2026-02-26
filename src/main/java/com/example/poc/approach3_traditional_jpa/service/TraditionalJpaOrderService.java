package com.example.poc.approach3_traditional_jpa.service;

import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaLineItem;
import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaOrder;
import com.example.poc.approach3_traditional_jpa.repository.TraditionalJpaLineItemRepo;
import com.example.poc.approach3_traditional_jpa.repository.TraditionalJpaOrderRepo;
import com.example.poc.domain.model.OrderAggregateSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class TraditionalJpaOrderService {

    private final TraditionalJpaOrderRepo orderRepo;
    private final TraditionalJpaLineItemRepo lineItemRepo;

    public TraditionalJpaOrderService(TraditionalJpaOrderRepo orderRepo,
                                      TraditionalJpaLineItemRepo lineItemRepo) {
        this.orderRepo = orderRepo;
        this.lineItemRepo = lineItemRepo;
    }

    public record OrderWithItems(TraditionalJpaOrder order, List<TraditionalJpaLineItem> items) {}

    public TraditionalJpaOrder createOrder(String customerId) {
        LocalDateTime now = LocalDateTime.now();
        TraditionalJpaOrder order = new TraditionalJpaOrder(
                UUID.randomUUID(), customerId, "DRAFT", BigDecimal.ZERO, now, now
        );
        return orderRepo.save(order);
    }

    public TraditionalJpaLineItem addLineItem(UUID orderId, String productId, String productName,
                                              int quantity, BigDecimal unitPrice) {
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        TraditionalJpaLineItem lineItem = new TraditionalJpaLineItem(
                UUID.randomUUID(), orderId, productId, productName, quantity, unitPrice, subtotal
        );
        TraditionalJpaLineItem saved = lineItemRepo.save(lineItem);

        recalculateTotal(orderId);

        return saved;
    }

    public OrderWithItems findOrderWithItems(UUID orderId) {
        TraditionalJpaOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        List<TraditionalJpaLineItem> items = lineItemRepo.findByOrderId(orderId);
        return new OrderWithItems(order, items);
    }

    @Transactional(readOnly = true)
    public TraditionalJpaOrder findOrderOnly(UUID orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
    }

    public void updateLineItemQuantity(UUID lineItemId, int newQuantity) {
        TraditionalJpaLineItem item = lineItemRepo.findById(lineItemId)
                .orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));
        item.setQuantity(newQuantity);
        item.setSubtotal(item.getUnitPrice().multiply(BigDecimal.valueOf(newQuantity)));
        lineItemRepo.save(item);
        recalculateTotal(item.getOrderId());
    }

    public void updateOrderStatus(UUID orderId, String newStatus) {
        TraditionalJpaOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        order.setStatus(newStatus);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }

    public void removeLineItem(UUID lineItemId) {
        TraditionalJpaLineItem item = lineItemRepo.findById(lineItemId)
                .orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));
        UUID orderId = item.getOrderId();
        lineItemRepo.delete(item);
        recalculateTotal(orderId);
    }

    @Transactional(readOnly = true)
    public List<TraditionalJpaOrder> findAllOrders() {
        return orderRepo.findAll();
    }

    /** 場景 K: 分頁查詢 */
    @Transactional(readOnly = true)
    public List<TraditionalJpaOrder> findAllOrdersPaged(int page, int size) {
        return orderRepo.findAll(PageRequest.of(page, size, Sort.by("createdAt"))).getContent();
    }

    /** 場景 L: 聚合報表 — 用 2 條 SQL (SUM + GROUP BY) 完成 */
    @Transactional(readOnly = true)
    public OrderAggregateSummary computeAggregateSummary() {
        long totalOrders = orderRepo.countOrders();
        BigDecimal totalAmount = orderRepo.sumTotalAmount();
        BigDecimal averageAmount = totalOrders > 0
                ? totalAmount.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Long> countByStatus = new HashMap<>();
        for (Object[] row : orderRepo.countByStatus()) {
            countByStatus.put((String) row[0], (Long) row[1]);
        }
        return new OrderAggregateSummary(totalOrders, totalAmount, averageAmount, countByStatus);
    }

    /** 場景 M: 批次狀態更新 — 1 條 UPDATE WHERE */
    public int bulkUpdateStatus(String from, String to) {
        return orderRepo.bulkUpdateStatus(from, to);
    }

    /** 場景 N: 跨 Aggregate 查詢 — SELECT JOIN WHERE product_id = ? */
    @Transactional(readOnly = true)
    public List<TraditionalJpaOrder> findByProductId(String productId) {
        List<UUID> orderIds = lineItemRepo.findOrderIdsByProductId(productId);
        return orderRepo.findAllById(orderIds);
    }

    public void deleteAll() {
        lineItemRepo.deleteAll();
        orderRepo.deleteAll();
    }

    private void recalculateTotal(UUID orderId) {
        List<TraditionalJpaLineItem> allItems = lineItemRepo.findByOrderId(orderId);
        BigDecimal total = allItems.stream()
                .map(TraditionalJpaLineItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TraditionalJpaOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        order.setTotalAmount(total);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }
}
