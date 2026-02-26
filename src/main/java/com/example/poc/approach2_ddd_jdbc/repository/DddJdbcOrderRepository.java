package com.example.poc.approach2_ddd_jdbc.repository;

import com.example.poc.domain.model.Order;
import com.example.poc.domain.model.OrderAggregateSummary;
import com.example.poc.domain.model.OrderLineItem;
import com.example.poc.domain.model.OrderStatus;
import com.example.poc.domain.repository.OrderRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository("dddJdbcOrderRepository")
public class DddJdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public DddJdbcOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── save (upsert Order + delete-then-insert line items) ──────────────

    @Override
    public Order save(Order order) {
        upsertOrder(order);
        replaceLineItems(order.getId(), order.getLineItems());
        return findById(order.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order not found after save: " + order.getId()));
    }

    private void upsertOrder(Order order) {
        String sql = """
                INSERT INTO orders (id, customer_id, status, total_amount, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    customer_id  = EXCLUDED.customer_id,
                    status       = EXCLUDED.status,
                    total_amount = EXCLUDED.total_amount,
                    created_at   = EXCLUDED.created_at,
                    updated_at   = EXCLUDED.updated_at,
                    version      = EXCLUDED.version
                """;

        jdbcTemplate.update(sql,
                order.getId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                Timestamp.valueOf(order.getCreatedAt()),
                Timestamp.valueOf(order.getUpdatedAt()),
                order.getVersion());
    }

    private void replaceLineItems(UUID orderId, List<OrderLineItem> lineItems) {
        jdbcTemplate.update("DELETE FROM order_line_items WHERE order_id = ?", orderId);

        if (lineItems.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO order_line_items (id, order_id, product_id, product_name, quantity, unit_price, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.batchUpdate(sql, lineItems, lineItems.size(),
                (ps, item) -> {
                    ps.setObject(1, item.id());
                    ps.setObject(2, orderId);
                    ps.setString(3, item.productId());
                    ps.setString(4, item.productName());
                    ps.setInt(5, item.quantity());
                    ps.setBigDecimal(6, item.unitPrice());
                    ps.setBigDecimal(7, item.subtotal());
                });
    }

    // ── findById (2 queries, hand-assembled via reconstitute) ────────────

    @Override
    public Optional<Order> findById(UUID id) {
        String orderSql = """
                SELECT id, customer_id, status, total_amount, created_at, updated_at, version
                FROM orders
                WHERE id = ?
                """;

        List<Order> orders = jdbcTemplate.query(orderSql,
                (rs, rowNum) -> mapOrder(rs, id),
                id);

        if (orders.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(orders.getFirst());
    }

    private Order mapOrder(ResultSet rs, UUID orderId) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        String customerId = rs.getString("customer_id");
        OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
        BigDecimal totalAmount = rs.getBigDecimal("total_amount");
        LocalDateTime createdAt = rs.getTimestamp("created_at").toLocalDateTime();
        LocalDateTime updatedAt = rs.getTimestamp("updated_at").toLocalDateTime();
        int version = rs.getInt("version");

        List<OrderLineItem> lineItems = findLineItemsByOrderId(orderId);

        return Order.reconstitute(id, customerId, status, totalAmount,
                lineItems, createdAt, updatedAt, version);
    }

    private List<OrderLineItem> findLineItemsByOrderId(UUID orderId) {
        String sql = """
                SELECT id, product_id, product_name, quantity, unit_price, subtotal
                FROM order_line_items
                WHERE order_id = ?
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new OrderLineItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("product_id"),
                        rs.getString("product_name"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("subtotal")),
                orderId);
    }

    // ── findAll (loads every aggregate in full) ─────────────────────────

    @Override
    public List<Order> findAll() {
        String sql = "SELECT id, customer_id, status, total_amount, created_at, updated_at, version FROM orders";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            return mapOrder(rs, id);
        });
    }

    // ── findAllPaged (場景 K: 分頁查詢) ──────────────────────────────────

    @Override
    public List<Order> findAllPaged(int page, int size) {
        String sql = """
                SELECT id, customer_id, status, total_amount, created_at, updated_at, version
                FROM orders
                ORDER BY created_at
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            return mapOrder(rs, id);
        }, size, page * size);
    }

    // ── computeAggregateSummary (場景 L: 聚合報表) ──────────────────────

    @Override
    public OrderAggregateSummary computeAggregateSummary() {
        // DDD 方式：載入所有 Aggregate 再用 Java Stream 計算
        List<Order> allOrders = findAll();
        long totalOrders = allOrders.size();
        BigDecimal totalAmount = allOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageAmount = totalOrders > 0
                ? totalAmount.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        Map<String, Long> countByStatus = new HashMap<>();
        for (Order order : allOrders) {
            countByStatus.merge(order.getStatus().name(), 1L, Long::sum);
        }
        return new OrderAggregateSummary(totalOrders, totalAmount, averageAmount, countByStatus);
    }

    // ── bulkUpdateStatus (場景 M: 批次狀態更新) ─────────────────────────

    @Override
    public int bulkUpdateStatus(OrderStatus from, OrderStatus to) {
        // DDD 方式：逐個載入 → 修改 → save (含 delete+insert items)
        List<Order> orders = findAll().stream()
                .filter(o -> o.getStatus() == from)
                .toList();

        for (Order order : orders) {
            Order updated = Order.reconstitute(
                    order.getId(), order.getCustomerId(), to, order.getTotalAmount(),
                    order.getLineItems(), order.getCreatedAt(), LocalDateTime.now(), order.getVersion());
            save(updated);
        }
        return orders.size();
    }

    // ── findByProductId (場景 N: 跨 Aggregate 查詢) ────────────────────

    @Override
    public List<Order> findByProductId(String productId) {
        // DDD 方式：findAll() + Java filter
        return findAll().stream()
                .filter(order -> order.getLineItems().stream()
                        .anyMatch(item -> item.productId().equals(productId)))
                .toList();
    }

    // ── deleteAll ────────────────────────────────────────────────────────

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM order_line_items");
        jdbcTemplate.update("DELETE FROM orders");
    }
}
