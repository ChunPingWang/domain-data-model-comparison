package com.example.poc.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderLineItem(
        UUID id,
        String productId,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {
    public OrderLineItem {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Unit price must be positive");
        }
        if (subtotal == null) {
            subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }

    public static OrderLineItem create(String productId, String productName, int quantity, BigDecimal unitPrice) {
        return new OrderLineItem(
                UUID.randomUUID(),
                productId,
                productName,
                quantity,
                unitPrice,
                unitPrice.multiply(BigDecimal.valueOf(quantity))
        );
    }
}
