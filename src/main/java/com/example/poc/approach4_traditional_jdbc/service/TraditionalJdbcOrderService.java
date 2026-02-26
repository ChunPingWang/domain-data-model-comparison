package com.example.poc.approach4_traditional_jdbc.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Traditional Data Model + Raw JDBC approach.
 *
 * <p>This implementation bypasses JPA entirely and works directly with
 * {@link JdbcTemplate} against the {@code jdbc_orders} and
 * {@code jdbc_order_line_items} tables. It demonstrates the performance
 * characteristics of hand-written SQL with no ORM overhead.</p>
 *
 * <p>Key design choice: the total amount is recalculated on the database
 * side using a {@code COALESCE(SUM(...))} subquery, avoiding the need to
 * load all line items into memory just to update the order total.</p>
 */
@Service
@Transactional
public class TraditionalJdbcOrderService {

    private final JdbcTemplate jdbcTemplate;

    public TraditionalJdbcOrderService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -----------------------------------------------------------------------
    // Inner record for bulk-insert convenience method
    // -----------------------------------------------------------------------

    public record LineItemInput(
            String productId,
            String productName,
            int quantity,
            BigDecimal unitPrice
    ) {}

    // -----------------------------------------------------------------------
    // Result records
    // -----------------------------------------------------------------------

    public record OrderWithItems(
            Map<String, Object> order,
            List<Map<String, Object>> lineItems
    ) {}

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    /**
     * Create a new empty order in DRAFT status.
     *
     * @param customerId the customer identifier
     * @return the generated order UUID
     */
    public UUID createOrder(String customerId) {
        UUID orderId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        jdbcTemplate.update(
                """
                INSERT INTO jdbc_orders (id, customer_id, status, total_amount, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                customerId,
                "DRAFT",
                BigDecimal.ZERO,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
        );

        return orderId;
    }

    /**
     * Add a single line item to an existing order and recalculate the total
     * on the database side.
     */
    public void addLineItem(UUID orderId, String productId, String productName,
                            int quantity, BigDecimal unitPrice) {
        UUID lineItemId = UUID.randomUUID();
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        // 1. Insert the line item
        jdbcTemplate.update(
                """
                INSERT INTO jdbc_order_line_items (id, order_id, product_id, product_name, quantity, unit_price, subtotal)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                lineItemId,
                orderId,
                productId,
                productName,
                quantity,
                unitPrice,
                subtotal
        );

        // 2. Recalculate total using DB-side aggregation (efficient: no Java-side loading)
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE jdbc_orders
                SET total_amount = (
                        SELECT COALESCE(SUM(subtotal), 0)
                        FROM jdbc_order_line_items
                        WHERE order_id = ?
                    ),
                    updated_at = ?
                WHERE id = ?
                """,
                orderId,
                Timestamp.valueOf(now),
                orderId
        );
    }

    /**
     * Convenience method: create an order and batch-insert all line items in
     * one transaction, then update the total once.
     *
     * @param customerId the customer identifier
     * @param items      the line items to add
     * @return the generated order UUID
     */
    public UUID createOrderWithItems(String customerId, List<LineItemInput> items) {
        // 1. Create the order header
        UUID orderId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();

        jdbcTemplate.update(
                """
                INSERT INTO jdbc_orders (id, customer_id, status, total_amount, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                orderId,
                customerId,
                "DRAFT",
                BigDecimal.ZERO,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now),
                0
        );

        // 2. Batch-insert all line items
        if (items != null && !items.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    """
                    INSERT INTO jdbc_order_line_items (id, order_id, product_id, product_name, quantity, unit_price, subtotal)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    items,
                    items.size(),
                    (ps, item) -> {
                        BigDecimal subtotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, orderId);
                        ps.setString(3, item.productId());
                        ps.setString(4, item.productName());
                        ps.setInt(5, item.quantity());
                        ps.setBigDecimal(6, item.unitPrice());
                        ps.setBigDecimal(7, subtotal);
                    }
            );

            // 3. Update total once (DB-side aggregation)
            jdbcTemplate.update(
                    """
                    UPDATE jdbc_orders
                    SET total_amount = (
                            SELECT COALESCE(SUM(subtotal), 0)
                            FROM jdbc_order_line_items
                            WHERE order_id = ?
                        ),
                        updated_at = ?
                    WHERE id = ?
                    """,
                    orderId,
                    Timestamp.valueOf(LocalDateTime.now()),
                    orderId
            );
        }

        return orderId;
    }

    public void updateLineItemQuantity(UUID lineItemId, int newQuantity, UUID orderId) {
        BigDecimal unitPrice = jdbcTemplate.queryForObject(
                "SELECT unit_price FROM jdbc_order_line_items WHERE id = ?",
                BigDecimal.class, lineItemId);
        BigDecimal newSubtotal = unitPrice.multiply(BigDecimal.valueOf(newQuantity));

        jdbcTemplate.update(
                "UPDATE jdbc_order_line_items SET quantity = ?, subtotal = ? WHERE id = ?",
                newQuantity, newSubtotal, lineItemId);

        jdbcTemplate.update(
                """
                UPDATE jdbc_orders SET total_amount = (
                    SELECT COALESCE(SUM(subtotal), 0) FROM jdbc_order_line_items WHERE order_id = ?
                ), updated_at = ? WHERE id = ?
                """,
                orderId, Timestamp.valueOf(LocalDateTime.now()), orderId);
    }

    public void updateOrderStatus(UUID orderId, String newStatus) {
        jdbcTemplate.update(
                "UPDATE jdbc_orders SET status = ?, updated_at = ? WHERE id = ?",
                newStatus, Timestamp.valueOf(LocalDateTime.now()), orderId);
    }

    public void removeLineItem(UUID lineItemId, UUID orderId) {
        jdbcTemplate.update("DELETE FROM jdbc_order_line_items WHERE id = ?", lineItemId);
        jdbcTemplate.update(
                """
                UPDATE jdbc_orders SET total_amount = (
                    SELECT COALESCE(SUM(subtotal), 0) FROM jdbc_order_line_items WHERE order_id = ?
                ), updated_at = ? WHERE id = ?
                """,
                orderId, Timestamp.valueOf(LocalDateTime.now()), orderId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllOrders() {
        return jdbcTemplate.queryForList("SELECT * FROM jdbc_orders ORDER BY created_at");
    }

    public UUID findFirstLineItemId(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM jdbc_order_line_items WHERE order_id = ? ORDER BY id LIMIT 1",
                UUID.class, orderId);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Load an order together with all its line items (two separate queries).
     */
    @Transactional(readOnly = true)
    public OrderWithItems findOrderWithItems(UUID orderId) {
        Map<String, Object> order = jdbcTemplate.queryForMap(
                "SELECT * FROM jdbc_orders WHERE id = ?",
                orderId
        );

        List<Map<String, Object>> lineItems = jdbcTemplate.queryForList(
                "SELECT * FROM jdbc_order_line_items WHERE order_id = ? ORDER BY id",
                orderId
        );

        return new OrderWithItems(order, lineItems);
    }

    /**
     * Load only the order header (no line items).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> findOrderOnly(UUID orderId) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM jdbc_orders WHERE id = ?",
                orderId
        );
    }

    // -----------------------------------------------------------------------
    // 場景 K: 分頁查詢
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAllOrdersPaged(int page, int size) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM jdbc_orders ORDER BY created_at LIMIT ? OFFSET ?",
                size, page * size);
    }

    // -----------------------------------------------------------------------
    // 場景 L: 聚合報表 — 2 SQL (SUM + GROUP BY)
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> computeAggregateSummary() {
        Map<String, Object> summary = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total_orders, COALESCE(SUM(total_amount), 0) AS total_amount, " +
                        "COALESCE(AVG(total_amount), 0) AS avg_amount FROM jdbc_orders");

        List<Map<String, Object>> groupBy = jdbcTemplate.queryForList(
                "SELECT status, COUNT(*) AS cnt FROM jdbc_orders GROUP BY status");

        summary.put("count_by_status", groupBy);
        return summary;
    }

    // -----------------------------------------------------------------------
    // 場景 M: 批次狀態更新 — 1 SQL UPDATE WHERE
    // -----------------------------------------------------------------------

    public int bulkUpdateStatus(String from, String to) {
        return jdbcTemplate.update(
                "UPDATE jdbc_orders SET status = ?, updated_at = ? WHERE status = ?",
                to, Timestamp.valueOf(LocalDateTime.now()), from);
    }

    // -----------------------------------------------------------------------
    // 場景 N: 跨 Aggregate 查詢 — SELECT JOIN WHERE product_id = ?
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOrdersByProductId(String productId) {
        return jdbcTemplate.queryForList(
                """
                SELECT DISTINCT o.*
                FROM jdbc_orders o
                JOIN jdbc_order_line_items li ON o.id = li.order_id
                WHERE li.product_id = ?
                ORDER BY o.created_at
                """,
                productId);
    }

    // -----------------------------------------------------------------------
    // 場景 O: 投影查詢 (DTO) — SELECT + COUNT + GROUP BY
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOrderProjections() {
        return jdbcTemplate.queryForList(
                """
                SELECT o.id, o.customer_id, COUNT(li.id) AS item_count,
                       o.total_amount
                FROM jdbc_orders o
                LEFT JOIN jdbc_order_line_items li ON o.id = li.order_id
                GROUP BY o.id, o.customer_id, o.total_amount
                ORDER BY o.created_at
                """);
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    /**
     * Delete all data from both tables (line items first due to FK constraint).
     */
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM jdbc_order_line_items");
        jdbcTemplate.update("DELETE FROM jdbc_orders");
    }
}
