package com.example.poc.approach1_ddd_jpa.repository;

import com.example.poc.approach1_ddd_jpa.entity.JpaOrderEntity;
import com.example.poc.approach1_ddd_jpa.entity.JpaOrderLineItemEntity;
import com.example.poc.domain.model.Order;
import com.example.poc.domain.model.OrderAggregateSummary;
import com.example.poc.domain.model.OrderLineItem;
import com.example.poc.domain.model.OrderStatus;
import com.example.poc.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DDD Repository implementation using JPA/Hibernate.
 * <p>
 * This class acts as an Anti-Corruption Layer (ACL) between the domain model
 * and the JPA persistence model. It converts between the rich domain objects
 * ({@link Order}, {@link OrderLineItem}) and the JPA entity classes
 * ({@link JpaOrderEntity}, {@link JpaOrderLineItemEntity}).
 */
@Repository("approach1OrderRepository")
@Transactional
public class DddJpaOrderRepository implements OrderRepository {

    private final SpringDataJpaOrderRepository springDataRepo;
    private final EntityManager entityManager;

    public DddJpaOrderRepository(SpringDataJpaOrderRepository springDataRepo, EntityManager entityManager) {
        this.springDataRepo = springDataRepo;
        this.entityManager = entityManager;
    }

    @Override
    public Order save(Order order) {
        JpaOrderEntity entity = toEntity(order);
        JpaOrderEntity saved = springDataRepo.save(entity);
        entityManager.flush();
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(UUID id) {
        return springDataRepo.findByIdWithLineItems(id)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return springDataRepo.findAll().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void deleteAll() {
        springDataRepo.deleteAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findAllPaged(int page, int size) {
        return springDataRepo.findAll(PageRequest.of(page, size)).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderAggregateSummary computeAggregateSummary() {
        // DDD 方式：載入所有 Aggregate 到記憶體，再用 Java Stream 計算
        List<Order> allOrders = findAll();
        long totalOrders = allOrders.size();
        BigDecimal totalAmount = allOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageAmount = totalOrders > 0
                ? totalAmount.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Long> countByStatus = allOrders.stream()
                .collect(Collectors.groupingBy(o -> o.getStatus().name(), Collectors.counting()));
        return new OrderAggregateSummary(totalOrders, totalAmount, averageAmount, countByStatus);
    }

    @Override
    public int bulkUpdateStatus(OrderStatus from, OrderStatus to) {
        int updated = springDataRepo.bulkUpdateStatus(from, to);
        entityManager.clear();
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findByProductId(String productId) {
        // DDD 方式：先找 order IDs，再逐個載入完整 Aggregate
        List<UUID> orderIds = springDataRepo.findOrderIdsByProductId(productId);
        return orderIds.stream()
                .map(id -> springDataRepo.findByIdWithLineItems(id))
                .filter(Optional::isPresent)
                .map(opt -> toDomain(opt.get()))
                .toList();
    }

    // ===== Anti-Corruption Layer: Mapping methods =====

    private JpaOrderEntity toEntity(Order order) {
        JpaOrderEntity entity = new JpaOrderEntity(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getVersion()
        );

        for (OrderLineItem item : order.getLineItems()) {
            JpaOrderLineItemEntity lineItemEntity = toLineItemEntity(item, entity);
            entity.addLineItem(lineItemEntity);
        }

        return entity;
    }

    private Order toDomain(JpaOrderEntity entity) {
        var lineItems = entity.getLineItems().stream()
                .map(this::toLineItemDomain)
                .toList();

        return Order.reconstitute(
                entity.getId(),
                entity.getCustomerId(),
                entity.getStatus(),
                entity.getTotalAmount(),
                lineItems,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    private JpaOrderLineItemEntity toLineItemEntity(OrderLineItem item, JpaOrderEntity orderEntity) {
        JpaOrderLineItemEntity entity = new JpaOrderLineItemEntity(
                item.id(),
                item.productId(),
                item.productName(),
                item.quantity(),
                item.unitPrice(),
                item.subtotal()
        );
        entity.setOrder(orderEntity);
        return entity;
    }

    private OrderLineItem toLineItemDomain(JpaOrderLineItemEntity entity) {
        return new OrderLineItem(
                entity.getId(),
                entity.getProductId(),
                entity.getProductName(),
                entity.getQuantity(),
                entity.getUnitPrice(),
                entity.getSubtotal()
        );
    }
}
