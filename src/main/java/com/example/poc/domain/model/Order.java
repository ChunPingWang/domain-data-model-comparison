package com.example.poc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Order {

    private UUID id;
    private String customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private final List<OrderLineItem> lineItems;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int version;

    private Order(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount,
                  List<OrderLineItem> lineItems, LocalDateTime createdAt, LocalDateTime updatedAt, int version) {
        this.id = id;
        this.customerId = customerId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.lineItems = new ArrayList<>(lineItems);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Order create(String customerId) {
        LocalDateTime now = LocalDateTime.now();
        return new Order(UUID.randomUUID(), customerId, OrderStatus.DRAFT,
                BigDecimal.ZERO, new ArrayList<>(), now, now, 0);
    }

    public static Order reconstitute(UUID id, String customerId, OrderStatus status, BigDecimal totalAmount,
                                     List<OrderLineItem> lineItems, LocalDateTime createdAt,
                                     LocalDateTime updatedAt, int version) {
        return new Order(id, customerId, status, totalAmount, lineItems, createdAt, updatedAt, version);
    }

    public void addLineItem(String productId, String productName, int quantity, BigDecimal unitPrice) {
        OrderLineItem item = OrderLineItem.create(productId, productName, quantity, unitPrice);
        this.lineItems.add(item);
        recalculateTotal();
        this.updatedAt = LocalDateTime.now();
    }

    public void submit() {
        if (this.status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Can only submit DRAFT orders");
        }
        if (this.lineItems.isEmpty()) {
            throw new IllegalStateException("Cannot submit order with no line items");
        }
        this.status = OrderStatus.SUBMITTED;
        this.updatedAt = LocalDateTime.now();
    }

    public void confirm() {
        if (this.status != OrderStatus.SUBMITTED) {
            throw new IllegalStateException("Can only confirm SUBMITTED orders");
        }
        this.status = OrderStatus.CONFIRMED;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateLineItemQuantity(UUID lineItemId, int newQuantity) {
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }
        List<OrderLineItem> updated = new ArrayList<>();
        boolean found = false;
        for (OrderLineItem item : this.lineItems) {
            if (item.id().equals(lineItemId)) {
                found = true;
                updated.add(new OrderLineItem(
                        item.id(), item.productId(), item.productName(),
                        newQuantity, item.unitPrice(),
                        item.unitPrice().multiply(BigDecimal.valueOf(newQuantity))
                ));
            } else {
                updated.add(item);
            }
        }
        if (!found) {
            throw new IllegalArgumentException("LineItem not found: " + lineItemId);
        }
        this.lineItems.clear();
        this.lineItems.addAll(updated);
        recalculateTotal();
        this.updatedAt = LocalDateTime.now();
    }

    public void removeLineItem(UUID lineItemId) {
        boolean removed = this.lineItems.removeIf(item -> item.id().equals(lineItemId));
        if (!removed) {
            throw new IllegalArgumentException("LineItem not found: " + lineItemId);
        }
        recalculateTotal();
        this.updatedAt = LocalDateTime.now();
    }

    private void recalculateTotal() {
        this.totalAmount = lineItems.stream()
                .map(OrderLineItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Getters
    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public List<OrderLineItem> getLineItems() { return Collections.unmodifiableList(lineItems); }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
}
