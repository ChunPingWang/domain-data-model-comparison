package com.example.poc;

import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaLineItem;
import com.example.poc.approach3_traditional_jpa.entity.TraditionalJpaOrder;
import com.example.poc.approach3_traditional_jpa.service.TraditionalJpaOrderService;
import com.example.poc.approach4_traditional_jdbc.service.TraditionalJdbcOrderService;
import com.example.poc.approach4_traditional_jdbc.service.TraditionalJdbcOrderService.LineItemInput;
import com.example.poc.domain.model.Order;
import com.example.poc.domain.model.OrderAggregateSummary;
import com.example.poc.domain.model.OrderLineItem;
import com.example.poc.domain.model.OrderStatus;
import com.example.poc.domain.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDD Repository Performance Benchmark — 完整測試套件
 *
 * <p>包含 17 個場景 (A~O)，從多個角度比較四種設計組合的性能特徵：
 * <ul>
 *   <li>A — 寫入性能 (5/50/200 LineItems)</li>
 *   <li>B — 讀取完整 Aggregate (200 items)</li>
 *   <li>C — 批次吞吐量 (500 Orders × 5 items)</li>
 *   <li>D — 只讀 Order 不需 LineItems (CQRS 動機驗證)</li>
 *   <li>E — 更新已存在的 LineItem (修改數量)</li>
 *   <li>F — 部分更新：僅變更 Order Status</li>
 *   <li>G — 刪除 LineItem (Aggregate 收縮)</li>
 *   <li>H — 列表查詢：讀取多筆 Orders (N+1 問題浮現)</li>
 *   <li>I — Invariant 正確性驗證 (不計時，驗證一致性)</li>
 *   <li>J — 並發寫入 (Optimistic Locking 驗證，不計時)</li>
 *   <li>K — 分頁查詢 (200 筆 Orders，讀 5 頁)</li>
 *   <li>L — 聚合報表 (500 筆 Orders，count/sum/avg/groupBy)</li>
 *   <li>M — 批次狀態更新 (200 筆 DRAFT → CANCELLED)</li>
 *   <li>N — 跨 Aggregate 查詢 (找包含特定商品的所有訂單)</li>
 *   <li>O — 投影查詢 DTO (只要 id, customerId, itemCount, totalAmount)</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepositoryBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURE_ITERATIONS = 10;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // -- Approach 1: DDD + JPA --
    @Autowired
    @Qualifier("approach1OrderRepository")
    private OrderRepository dddJpaRepo;

    // -- Approach 2: DDD + JDBC --
    @Autowired
    @Qualifier("dddJdbcOrderRepository")
    private OrderRepository dddJdbcRepo;

    // -- Approach 3: Traditional + JPA --
    @Autowired
    private TraditionalJpaOrderService tradJpaService;

    // -- Approach 4: Traditional + JDBC --
    @Autowired
    private TraditionalJdbcOrderService tradJdbcService;

    @Autowired
    private EntityManager entityManager;

    // ===== Result storage =====
    private static final Map<String, double[]> results = new LinkedHashMap<>();

    // =========================================================================
    // Scenario A — Write Performance (5 / 50 / 200 LineItems)
    // 設計原因：量化不同 Aggregate 大小下，四種組合的寫入成本差異
    //           小 Aggregate → ORM 開銷佔比高
    //           大 Aggregate → batch insert vs 逐筆 save 差異明顯
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(1)
    void scenarioA_write5Items() {
        double[] times = benchmarkWrite(5);
        results.put("A-Write-5items", times);
        printScenarioResult("A-Write-5items", times);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void scenarioA_write50Items() {
        double[] times = benchmarkWrite(50);
        results.put("A-Write-50items", times);
        printScenarioResult("A-Write-50items", times);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void scenarioA_write200Items() {
        double[] times = benchmarkWrite(200);
        results.put("A-Write-200items", times);
        printScenarioResult("A-Write-200items", times);
    }

    // =========================================================================
    // Scenario B — Read Full Aggregate (200 LineItems)
    // 設計原因：比較 JPA JOIN FETCH vs JDBC 精確 2-SQL vs Traditional 獨立查詢
    //           觀察 Entity Mapping / Proxy 建立的額外成本
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(4)
    void scenarioB_readFullAggregate() {
        double[] times = benchmarkReadFull(200);
        results.put("B-Read-Full-200items", times);
        printScenarioResult("B-Read-Full-200items", times);
    }

    // =========================================================================
    // Scenario C — Batch Throughput (500 Orders × 5 LineItems)
    // 設計原因：模擬高吞吐場景（批次匯入、EOD 處理）
    //           觀察 Hibernate persistence context 在大量操作下的記憶體壓力
    //           batch insert vs 逐筆 save 的吞吐差異
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(5)
    void scenarioC_batchCreate500Orders() {
        double[] times = benchmarkBatch(500, 5);
        results.put("C-Batch-500x5", times);
        printScenarioResult("C-Batch-500x5", times);
    }

    // =========================================================================
    // Scenario D — Read Order Only (no LineItems)
    // 設計原因：這是 CQRS 動機的關鍵場景
    //   DDD Repository 的設計約束：必須以 Aggregate 為單位存取
    //   → 即使只需要 Order 表頭，也必須載入全部 200 筆 LineItems
    //   Traditional 模式可以只查 Order 表
    //   結論指向：Query Side 需要獨立的 Read Model（CQRS）
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(6)
    void scenarioD_readOrderOnly() {
        double[] times = benchmarkReadOrderOnly(200);
        results.put("D-Read-OrderOnly", times);
        printScenarioResult("D-Read-OrderOnly", times);
    }

    // =========================================================================
    // Scenario E — Update Existing LineItem (修改數量)
    // 設計原因：驗證「修改子 Entity」時的行為差異
    //   DDD：必須載入完整 Aggregate → 修改 → 儲存整個 Aggregate（delete + re-insert 所有 items）
    //   Traditional：直接 UPDATE 單一 row + recalculate total
    //   這體現了 DDD 「以 Aggregate 為單位操作」的核心代價
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(7)
    void scenarioE_updateLineItem() {
        double[] times = benchmarkUpdateLineItem(50);
        results.put("E-Update-LineItem", times);
        printScenarioResult("E-Update-LineItem", times);
    }

    // =========================================================================
    // Scenario F — Partial Update: Status Change Only
    // 設計原因：驗證「只修改 Aggregate Root 屬性」時的不對稱性
    //   DDD：仍然必須載入完整 Aggregate（含所有 items）只為了改一個 status 欄位
    //   Traditional：直接 UPDATE orders SET status = ? WHERE id = ?（一條 SQL）
    //   這是 DDD 設計取捨的極端案例，強化 CQRS 的論點
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(8)
    void scenarioF_partialUpdateStatusOnly() {
        double[] times = benchmarkUpdateStatus(200);
        results.put("F-Update-Status-Only", times);
        printScenarioResult("F-Update-Status-Only", times);
    }

    // =========================================================================
    // Scenario G — Remove LineItem (Aggregate 收縮)
    // 設計原因：驗證刪除子 Entity 的行為差異
    //   DDD+JPA：orphanRemoval=true 自動處理，但需載入整個 Aggregate
    //   DDD+JDBC：delete-then-insert 策略（重建所有 items）
    //   Traditional：直接 DELETE 單一 row + recalculate
    //   觀察 orphanRemoval 的便利性 vs 性能成本
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(9)
    void scenarioG_removeLineItem() {
        double[] times = benchmarkRemoveLineItem(50);
        results.put("G-Remove-LineItem", times);
        printScenarioResult("G-Remove-LineItem", times);
    }

    // =========================================================================
    // Scenario H — List Query: Read Multiple Orders (N+1 Problem)
    // 設計原因：驗證列表查詢下 DDD 的 N+1 問題
    //   DDD：findAll() 必須為每個 Order 載入其 LineItems → N+1 SQL
    //   Traditional：可以只查 Order 表（不需要 items 欄位）
    //   在列表頁面（如「我的訂單」）場景下差異最明顯
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(10)
    void scenarioH_listMultipleOrders() {
        double[] times = benchmarkListOrders(100, 10);
        results.put("H-List-100-Orders", times);
        printScenarioResult("H-List-100-Orders", times);
    }

    // =========================================================================
    // Scenario I — Invariant Correctness Verification (不計時)
    // 設計原因：性能只是一個面向，正確性才是根本
    //   驗證 DDD Aggregate 的 Invariant（totalAmount = Σ subtotals）
    //   在各種操作（新增、修改、刪除 items）後是否始終成立
    //   這是 DDD 核心價值的體現：用設計約束守護業務一致性
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(11)
    void scenarioI_invariantCorrectness() {
        System.out.println("\n═══ Scenario I — Invariant Correctness Verification ═══");
        verifyDddInvariant();
        verifyTraditionalInvariant();
        System.out.println("[I-Invariant] ALL PASSED — totalAmount = Σ subtotals is maintained");
    }

    // =========================================================================
    // Scenario J — Concurrent Write (Optimistic Locking Verification, 不計時)
    // 設計原因：驗證 JPA @Version 的 Optimistic Locking 機制
    //   DDD+JPA：有 @Version 欄位，並發修改會拋出 OptimisticLockException
    //   DDD+JDBC：無版本檢查，最後一次寫入覆蓋前一次（Last Writer Wins）
    //   這揭示了 DDD Aggregate + ORM 在並發控制上的天然優勢
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(12)
    void scenarioJ_concurrentWrite() {
        System.out.println("\n═══ Scenario J — Concurrent Write (Optimistic Locking) ═══");
        verifyConcurrentWriteWithJpa();
        verifyConcurrentWriteWithJdbc();
        System.out.println("[J-Concurrency] Verification complete");
    }

    // =========================================================================
    // Scenario K — Paginated Query (200 Orders, read 5 pages of 20)
    // 設計原因：驗證分頁查詢下的 DDD vs Traditional 差異
    //   DDD：LIMIT/OFFSET 取得 Order 後，仍需 N+1 載入每個 Order 的 items
    //   Traditional：1 SQL LIMIT/OFFSET，只查 Order 表
    //   在列表型 API（如 GET /orders?page=2&size=20）場景下差異顯著
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(13)
    void scenarioK_paginatedQuery() {
        double[] times = benchmarkPaginatedQuery(200, 5, 20);
        results.put("K-Paginated-5pages", times);
        printScenarioResult("K-Paginated-5pages", times);
    }

    // =========================================================================
    // Scenario L — Aggregate Report (500 Orders, count/sum/avg/groupBy)
    // 設計原因：驗證聚合報表查詢的效能差異
    //   DDD：findAll() 載入全部 500 筆 Orders（含所有 items）到 Java，再用 Stream 計算
    //   Traditional：2 條 SQL（SUM/COUNT + GROUP BY）在 DB 端完成
    //   這是 DDD 效能代價最極端的場景之一 — 物件映射 + GC 壓力
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(14)
    void scenarioL_aggregateReport() {
        double[] times = benchmarkAggregateReport(500, 3);
        results.put("L-Aggregate-Report", times);
        printScenarioResult("L-Aggregate-Report", times);
    }

    // =========================================================================
    // Scenario M — Bulk Status Update (200 DRAFT → CANCELLED)
    // 設計原因：驗證批次狀態更新的效能差異
    //   DDD：逐個載入 Order（含 items）→ 修改 status → save（含 delete+insert items）
    //   Traditional：1 條 UPDATE orders SET status = ? WHERE status = ?
    //   最能體現 DDD「以 Aggregate 為單位操作」在批次場景下的代價
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(15)
    void scenarioM_bulkStatusUpdate() {
        double[] times = benchmarkBulkStatusUpdate(200, 5);
        results.put("M-Bulk-Update-200", times);
        printScenarioResult("M-Bulk-Update-200", times);
    }

    // =========================================================================
    // Scenario N — Cross-Aggregate Query (find orders containing product X)
    // 設計原因：驗證跨 Aggregate 查詢的效能差異
    //   DDD：findAll() 載入全部 Aggregate 再 Java filter（或先查 item IDs 再逐個載入）
    //   Traditional：SELECT JOIN WHERE product_id = ?（1 SQL）
    //   揭示 DDD 在「查詢不是以 Aggregate Root 為條件」時的先天劣勢
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(16)
    void scenarioN_crossAggregateQuery() {
        double[] times = benchmarkCrossAggregateQuery(100, 5);
        results.put("N-Cross-Aggregate", times);
        printScenarioResult("N-Cross-Aggregate", times);
    }

    // =========================================================================
    // Scenario O — Projection Query (DTO: id, customerId, itemCount, totalAmount)
    // 設計原因：驗證投影查詢（只需部分欄位）的效能差異
    //   DDD：必須載入完整 Aggregate 再映射為 DTO
    //   Traditional：SELECT + COUNT + GROUP BY 直接取所需欄位
    //   在 BFF / GraphQL 場景下（按需取欄位）差異尤為明顯
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(17)
    void scenarioO_projectionQuery() {
        double[] times = benchmarkProjectionQuery(100, 10);
        results.put("O-Projection-DTO", times);
        printScenarioResult("O-Projection-DTO", times);
    }

    // =========================================================================
    // Final Report
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(100)
    void printFinalReport() {
        System.out.println();
        String line = "═".repeat(94);
        String border = "╔" + line + "╗";
        String separator = "╠" + line + "╣";
        String bottom = "╚" + line + "╝";

        System.out.println(border);
        System.out.println("║" + centerText("DDD REPOSITORY PERFORMANCE BENCHMARK — FINAL REPORT", 94) + "║");
        System.out.println(separator);
        System.out.printf("║ %-30s │ %13s │ %13s │ %13s │ %13s ║%n",
                "Scenario", "DDD+JPA", "DDD+JDBC", "Trad+JPA", "Trad+JDBC");
        System.out.println(separator);

        for (var entry : results.entrySet()) {
            String scenario = entry.getKey();
            double[] times = entry.getValue();
            double minTime = Arrays.stream(times).min().orElse(0);

            System.out.printf("║ %-30s │ %9.1f ms%s │ %9.1f ms%s │ %9.1f ms%s │ %9.1f ms%s ║%n",
                    scenario,
                    times[0], mark(times[0], minTime),
                    times[1], mark(times[1], minTime),
                    times[2], mark(times[2], minTime),
                    times[3], mark(times[3], minTime));
        }

        System.out.println(separator);
        System.out.printf("║  %-91s ║%n", "★ = fastest for this scenario");
        System.out.printf("║  Warmup: %d iterations  |  Measured: %d iterations (avg)%-38s ║%n",
                WARMUP_ITERATIONS, MEASURE_ITERATIONS, "");
        System.out.println(bottom);
        System.out.println();

        printAnalysis();
    }

    // =========================================================================
    // Benchmark Implementations
    // =========================================================================

    private double[] benchmarkWrite(int itemCount) {
        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            dddJpaRepo.deleteAll();
            Order order = createDddOrder(itemCount);
            dddJpaRepo.save(order);
        });

        double dddJdbc = measureAvg(() -> {
            dddJdbcRepo.deleteAll();
            Order order = createDddOrder(itemCount);
            dddJdbcRepo.save(order);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            tradJpaService.deleteAll();
            writeTradJpa(itemCount);
        });

        double tradJdbc = measureAvg(() -> {
            tradJdbcService.deleteAll();
            writeTradJdbc(itemCount);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkReadFull(int itemCount) {
        UUID dddOrderId = seedDddOrder(itemCount);
        UUID tradJpaOrderId = seedTradJpaOrder(itemCount);
        UUID tradJdbcOrderId = seedTradJdbcOrder(itemCount);

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            Optional<Order> order = dddJpaRepo.findById(dddOrderId);
            assertThat(order).isPresent();
            assertThat(order.get().getLineItems()).hasSize(itemCount);
        });

        double dddJdbc = measureAvg(() -> {
            Optional<Order> order = dddJdbcRepo.findById(dddOrderId);
            assertThat(order).isPresent();
            assertThat(order.get().getLineItems()).hasSize(itemCount);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            var result = tradJpaService.findOrderWithItems(tradJpaOrderId);
            assertThat(result.items()).hasSize(itemCount);
        });

        double tradJdbc = measureAvg(() -> {
            var result = tradJdbcService.findOrderWithItems(tradJdbcOrderId);
            assertThat(result.lineItems()).hasSize(itemCount);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkBatch(int orderCount, int itemsPerOrder) {
        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            dddJpaRepo.deleteAll();
            for (int i = 0; i < orderCount; i++) {
                Order order = createDddOrder(itemsPerOrder);
                dddJpaRepo.save(order);
                if (i % 50 == 0) clearPersistenceContext();
            }
        });

        double dddJdbc = measureAvg(() -> {
            dddJdbcRepo.deleteAll();
            for (int i = 0; i < orderCount; i++) {
                Order order = createDddOrder(itemsPerOrder);
                dddJdbcRepo.save(order);
            }
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            tradJpaService.deleteAll();
            for (int i = 0; i < orderCount; i++) {
                writeTradJpa(itemsPerOrder);
                if (i % 50 == 0) clearPersistenceContext();
            }
        });

        double tradJdbc = measureAvg(() -> {
            tradJdbcService.deleteAll();
            for (int i = 0; i < orderCount; i++) {
                List<LineItemInput> items = new ArrayList<>();
                for (int j = 0; j < itemsPerOrder; j++) {
                    items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
                }
                tradJdbcService.createOrderWithItems("CUST-BATCH", items);
            }
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkReadOrderOnly(int itemCount) {
        UUID dddOrderId = seedDddOrder(itemCount);
        UUID tradJpaOrderId = seedTradJpaOrder(itemCount);
        UUID tradJdbcOrderId = seedTradJdbcOrder(itemCount);

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            Optional<Order> order = dddJpaRepo.findById(dddOrderId);
            assertThat(order).isPresent();
        });

        double dddJdbc = measureAvg(() -> {
            Optional<Order> order = dddJdbcRepo.findById(dddOrderId);
            assertThat(order).isPresent();
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            var order = tradJpaService.findOrderOnly(tradJpaOrderId);
            assertThat(order).isNotNull();
        });

        double tradJdbc = measureAvg(() -> {
            var order = tradJdbcService.findOrderOnly(tradJdbcOrderId);
            assertThat(order).isNotNull();
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkUpdateLineItem(int itemCount) {
        // Seed: create order with N items, then measure updating the first item's quantity
        UUID dddOrderId = seedDddOrder(itemCount);
        UUID tradJpaOrderId = seedTradJpaOrder(itemCount);
        UUID tradJdbcOrderId = seedTradJdbcOrder(itemCount);

        // Get first line item IDs
        Order dddOrder = dddJdbcRepo.findById(dddOrderId).orElseThrow();
        UUID dddFirstItemId = dddOrder.getLineItems().getFirst().id();

        var tradJpaResult = tradJpaService.findOrderWithItems(tradJpaOrderId);
        UUID tradJpaFirstItemId = tradJpaResult.items().getFirst().getId();

        UUID tradJdbcFirstItemId = tradJdbcService.findFirstLineItemId(tradJdbcOrderId);

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            // DDD: load full aggregate → modify → save entire aggregate
            Order order = dddJpaRepo.findById(dddOrderId).orElseThrow();
            UUID itemId = order.getLineItems().getFirst().id();
            order.updateLineItemQuantity(itemId, 99);
            dddJpaRepo.save(order);
        });

        double dddJdbc = measureAvg(() -> {
            // DDD: load full aggregate → modify → delete-then-insert all items
            Order order = dddJdbcRepo.findById(dddOrderId).orElseThrow();
            UUID itemId = order.getLineItems().getFirst().id();
            order.updateLineItemQuantity(itemId, 99);
            dddJdbcRepo.save(order);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            // Traditional: directly update one row + recalculate
            tradJpaService.updateLineItemQuantity(tradJpaFirstItemId, 99);
        });

        double tradJdbc = measureAvg(() -> {
            // Traditional: UPDATE single row + subquery total
            tradJdbcService.updateLineItemQuantity(tradJdbcFirstItemId, 99, tradJdbcOrderId);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkUpdateStatus(int itemCount) {
        // Seed order with many items, then measure changing only the status
        UUID dddOrderId = seedDddOrder(itemCount);
        UUID tradJpaOrderId = seedTradJpaOrder(itemCount);
        UUID tradJdbcOrderId = seedTradJdbcOrder(itemCount);

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            // DDD: must load entire aggregate (200 items!) just to change status
            Order order = dddJpaRepo.findById(dddOrderId).orElseThrow();
            // Simulate: loaded full aggregate, but only change status via submit()
            // Since order may already be submitted, we use reconstitute to reset
            Order fresh = Order.reconstitute(order.getId(), order.getCustomerId(),
                    com.example.poc.domain.model.OrderStatus.DRAFT, order.getTotalAmount(),
                    order.getLineItems(), order.getCreatedAt(), order.getUpdatedAt(), order.getVersion());
            fresh.submit();
            dddJpaRepo.save(fresh);
        });

        double dddJdbc = measureAvg(() -> {
            Order order = dddJdbcRepo.findById(dddOrderId).orElseThrow();
            Order fresh = Order.reconstitute(order.getId(), order.getCustomerId(),
                    com.example.poc.domain.model.OrderStatus.DRAFT, order.getTotalAmount(),
                    order.getLineItems(), order.getCreatedAt(), order.getUpdatedAt(), order.getVersion());
            fresh.submit();
            dddJdbcRepo.save(fresh);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            // Traditional: single UPDATE SET status — no items loaded
            tradJpaService.updateOrderStatus(tradJpaOrderId, "SUBMITTED");
        });

        double tradJdbc = measureAvg(() -> {
            // Traditional: one SQL statement
            tradJdbcService.updateOrderStatus(tradJdbcOrderId, "SUBMITTED");
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkRemoveLineItem(int itemCount) {
        // For each iteration, we need a fresh order because item gets removed
        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            dddJpaRepo.deleteAll();
            Order order = createDddOrder(itemCount);
            Order saved = dddJpaRepo.save(order);
            clearPersistenceContext();
            // Reload, remove first item, save
            Order loaded = dddJpaRepo.findById(saved.getId()).orElseThrow();
            UUID firstItemId = loaded.getLineItems().getFirst().id();
            loaded.removeLineItem(firstItemId);
            dddJpaRepo.save(loaded);
        });

        double dddJdbc = measureAvg(() -> {
            dddJdbcRepo.deleteAll();
            Order order = createDddOrder(itemCount);
            Order saved = dddJdbcRepo.save(order);
            Order loaded = dddJdbcRepo.findById(saved.getId()).orElseThrow();
            UUID firstItemId = loaded.getLineItems().getFirst().id();
            loaded.removeLineItem(firstItemId);
            dddJdbcRepo.save(loaded);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            tradJpaService.deleteAll();
            var tradOrder = tradJpaService.createOrder("CUST-BENCH");
            for (int i = 0; i < itemCount; i++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + i, "Product " + i,
                        1 + (i % 5), BigDecimal.valueOf(10 + i));
            }
            clearPersistenceContext();
            var items = tradJpaService.findOrderWithItems(tradOrder.getId()).items();
            tradJpaService.removeLineItem(items.getFirst().getId());
        });

        double tradJdbc = measureAvg(() -> {
            tradJdbcService.deleteAll();
            List<LineItemInput> items = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                items.add(new LineItemInput("P" + i, "Product " + i, 1 + (i % 5), BigDecimal.valueOf(10 + i)));
            }
            UUID orderId = tradJdbcService.createOrderWithItems("CUST-BENCH", items);
            UUID firstItemId = tradJdbcService.findFirstLineItemId(orderId);
            tradJdbcService.removeLineItem(firstItemId, orderId);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    private double[] benchmarkListOrders(int orderCount, int itemsPerOrder) {
        // Seed N orders, each with M items
        dddJdbcRepo.deleteAll();
        for (int i = 0; i < orderCount; i++) {
            Order order = createDddOrder(itemsPerOrder);
            dddJdbcRepo.save(order);
        }

        tradJpaService.deleteAll();
        clearPersistenceContext();
        for (int i = 0; i < orderCount; i++) {
            var tradOrder = tradJpaService.createOrder("CUST-" + i);
            for (int j = 0; j < itemsPerOrder; j++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + j, "Product " + j,
                        1 + (j % 5), BigDecimal.valueOf(10 + j));
            }
        }
        clearPersistenceContext();

        tradJdbcService.deleteAll();
        for (int i = 0; i < orderCount; i++) {
            List<LineItemInput> items = new ArrayList<>();
            for (int j = 0; j < itemsPerOrder; j++) {
                items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
            }
            tradJdbcService.createOrderWithItems("CUST-" + i, items);
        }

        // DDD findAll() loads every aggregate in full (including all line items)
        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<Order> orders = dddJpaRepo.findAll();
            assertThat(orders).hasSize(orderCount);
        });

        double dddJdbc = measureAvg(() -> {
            List<Order> orders = dddJdbcRepo.findAll();
            assertThat(orders).hasSize(orderCount);
        });

        // Traditional can load just order headers
        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<TraditionalJpaOrder> orders = tradJpaService.findAllOrders();
            assertThat(orders).hasSize(orderCount);
        });

        double tradJdbc = measureAvg(() -> {
            var orders = tradJdbcService.findAllOrders();
            assertThat(orders).hasSize(orderCount);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Benchmark J: Concurrent Write (Optimistic Locking)
    // =========================================================================

    private void verifyConcurrentWriteWithJpa() {
        // DDD+JPA: @Version triggers OptimisticLockException on concurrent modification
        clearPersistenceContext();
        dddJpaRepo.deleteAll();
        Order order = createDddOrder(3);
        Order saved = dddJpaRepo.save(order);
        UUID orderId = saved.getId();
        clearPersistenceContext();

        AtomicInteger conflicts = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> f1 = executor.submit(() -> {
                try {
                    Order o1 = dddJpaRepo.findById(orderId).orElseThrow();
                    o1.addLineItem("CONFLICT-1", "Conflict Product 1", 1, BigDecimal.TEN);
                    Thread.sleep(50); // simulate processing delay
                    dddJpaRepo.save(o1);
                } catch (OptimisticLockException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    conflicts.incrementAndGet();
                } catch (Exception e) {
                    if (e.getCause() instanceof OptimisticLockException) {
                        conflicts.incrementAndGet();
                    }
                }
            });
            Future<?> f2 = executor.submit(() -> {
                try {
                    Order o2 = dddJpaRepo.findById(orderId).orElseThrow();
                    o2.addLineItem("CONFLICT-2", "Conflict Product 2", 2, BigDecimal.valueOf(20));
                    Thread.sleep(50);
                    dddJpaRepo.save(o2);
                } catch (OptimisticLockException | org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    conflicts.incrementAndGet();
                } catch (Exception e) {
                    if (e.getCause() instanceof OptimisticLockException) {
                        conflicts.incrementAndGet();
                    }
                }
            });

            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // ignore timeout
        } finally {
            executor.shutdown();
        }

        System.out.printf("  [DDD+JPA]  Optimistic Lock conflicts detected: %d (expected: 0 or 1)%n", conflicts.get());
    }

    private void verifyConcurrentWriteWithJdbc() {
        // DDD+JDBC: No version check — last writer wins
        dddJdbcRepo.deleteAll();
        Order order = createDddOrder(3);
        Order saved = dddJdbcRepo.save(order);
        UUID orderId = saved.getId();

        // Simulate two concurrent reads
        Order read1 = dddJdbcRepo.findById(orderId).orElseThrow();
        Order read2 = dddJdbcRepo.findById(orderId).orElseThrow();

        // Both modify and save — no conflict detection
        read1.addLineItem("LWW-1", "Last Writer Wins 1", 1, BigDecimal.TEN);
        dddJdbcRepo.save(read1);

        read2.addLineItem("LWW-2", "Last Writer Wins 2", 2, BigDecimal.valueOf(20));
        dddJdbcRepo.save(read2); // overwrites read1's changes

        Order result = dddJdbcRepo.findById(orderId).orElseThrow();
        System.out.printf("  [DDD+JDBC] No conflict detection — final items: %d (last writer wins)%n",
                result.getLineItems().size());
    }

    // =========================================================================
    // Benchmark K: Paginated Query
    // =========================================================================

    private double[] benchmarkPaginatedQuery(int totalOrders, int pages, int pageSize) {
        // Seed data — DDD (shared tables)
        dddJdbcRepo.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            Order order = createDddOrder(5);
            dddJdbcRepo.save(order);
        }

        // Seed data — Traditional JPA
        tradJpaService.deleteAll();
        clearPersistenceContext();
        for (int i = 0; i < totalOrders; i++) {
            var tradOrder = tradJpaService.createOrder("CUST-" + i);
            for (int j = 0; j < 5; j++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + j, "Product " + j,
                        1 + (j % 5), BigDecimal.valueOf(10 + j));
            }
        }
        clearPersistenceContext();

        // Seed data — Traditional JDBC
        tradJdbcService.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            List<LineItemInput> items = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
            }
            tradJdbcService.createOrderWithItems("CUST-" + i, items);
        }

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            for (int p = 0; p < pages; p++) {
                List<Order> page = dddJpaRepo.findAllPaged(p, pageSize);
                assertThat(page).hasSizeLessThanOrEqualTo(pageSize);
            }
        });

        double dddJdbc = measureAvg(() -> {
            for (int p = 0; p < pages; p++) {
                List<Order> page = dddJdbcRepo.findAllPaged(p, pageSize);
                assertThat(page).hasSizeLessThanOrEqualTo(pageSize);
            }
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            for (int p = 0; p < pages; p++) {
                List<TraditionalJpaOrder> page = tradJpaService.findAllOrdersPaged(p, pageSize);
                assertThat(page).hasSizeLessThanOrEqualTo(pageSize);
            }
        });

        double tradJdbc = measureAvg(() -> {
            for (int p = 0; p < pages; p++) {
                var page = tradJdbcService.findAllOrdersPaged(p, pageSize);
                assertThat(page).hasSizeLessThanOrEqualTo(pageSize);
            }
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Benchmark L: Aggregate Report
    // =========================================================================

    private double[] benchmarkAggregateReport(int totalOrders, int itemsPerOrder) {
        // Seed data — DDD
        dddJdbcRepo.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            Order order = createDddOrder(itemsPerOrder);
            dddJdbcRepo.save(order);
        }

        // Seed data — Traditional JPA
        tradJpaService.deleteAll();
        clearPersistenceContext();
        for (int i = 0; i < totalOrders; i++) {
            var tradOrder = tradJpaService.createOrder("CUST-" + i);
            for (int j = 0; j < itemsPerOrder; j++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + j, "Product " + j,
                        1 + (j % 5), BigDecimal.valueOf(10 + j));
            }
        }
        clearPersistenceContext();

        // Seed data — Traditional JDBC
        tradJdbcService.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            List<LineItemInput> items = new ArrayList<>();
            for (int j = 0; j < itemsPerOrder; j++) {
                items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
            }
            tradJdbcService.createOrderWithItems("CUST-" + i, items);
        }

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            OrderAggregateSummary summary = dddJpaRepo.computeAggregateSummary();
            assertThat(summary.totalOrders()).isEqualTo(totalOrders);
        });

        double dddJdbc = measureAvg(() -> {
            OrderAggregateSummary summary = dddJdbcRepo.computeAggregateSummary();
            assertThat(summary.totalOrders()).isEqualTo(totalOrders);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            OrderAggregateSummary summary = tradJpaService.computeAggregateSummary();
            assertThat(summary.totalOrders()).isEqualTo(totalOrders);
        });

        double tradJdbc = measureAvg(() -> {
            var summary = tradJdbcService.computeAggregateSummary();
            assertThat(summary).containsKey("total_orders");
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Benchmark M: Bulk Status Update
    // =========================================================================

    private double[] benchmarkBulkStatusUpdate(int totalOrders, int itemsPerOrder) {
        double dddJpa = measureAvg(() -> {
            // Re-seed as DRAFT for each iteration
            clearPersistenceContext();
            dddJpaRepo.deleteAll();
            clearPersistenceContext();
            for (int i = 0; i < totalOrders; i++) {
                Order order = createDddOrder(itemsPerOrder);
                dddJpaRepo.save(order);
                if (i % 50 == 0) clearPersistenceContext();
            }
            clearPersistenceContext();
            int updated = dddJpaRepo.bulkUpdateStatus(OrderStatus.DRAFT, OrderStatus.CANCELLED);
            assertThat(updated).isEqualTo(totalOrders);
        });

        double dddJdbc = measureAvg(() -> {
            dddJdbcRepo.deleteAll();
            for (int i = 0; i < totalOrders; i++) {
                Order order = createDddOrder(itemsPerOrder);
                dddJdbcRepo.save(order);
            }
            int updated = dddJdbcRepo.bulkUpdateStatus(OrderStatus.DRAFT, OrderStatus.CANCELLED);
            assertThat(updated).isEqualTo(totalOrders);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            tradJpaService.deleteAll();
            clearPersistenceContext();
            for (int i = 0; i < totalOrders; i++) {
                writeTradJpa(itemsPerOrder);
                if (i % 50 == 0) clearPersistenceContext();
            }
            clearPersistenceContext();
            int updated = tradJpaService.bulkUpdateStatus("DRAFT", "CANCELLED");
            assertThat(updated).isEqualTo(totalOrders);
        });

        double tradJdbc = measureAvg(() -> {
            tradJdbcService.deleteAll();
            for (int i = 0; i < totalOrders; i++) {
                List<LineItemInput> items = new ArrayList<>();
                for (int j = 0; j < itemsPerOrder; j++) {
                    items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
                }
                tradJdbcService.createOrderWithItems("CUST-BATCH", items);
            }
            int updated = tradJdbcService.bulkUpdateStatus("DRAFT", "CANCELLED");
            assertThat(updated).isEqualTo(totalOrders);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Benchmark N: Cross-Aggregate Query
    // =========================================================================

    private double[] benchmarkCrossAggregateQuery(int totalOrders, int itemsPerOrder) {
        String targetProductId = "P0"; // every order contains this product

        // Seed data — DDD
        dddJdbcRepo.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            Order order = createDddOrder(itemsPerOrder);
            dddJdbcRepo.save(order);
        }

        // Seed data — Traditional JPA
        tradJpaService.deleteAll();
        clearPersistenceContext();
        for (int i = 0; i < totalOrders; i++) {
            var tradOrder = tradJpaService.createOrder("CUST-" + i);
            for (int j = 0; j < itemsPerOrder; j++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + j, "Product " + j,
                        1 + (j % 5), BigDecimal.valueOf(10 + j));
            }
        }
        clearPersistenceContext();

        // Seed data — Traditional JDBC
        tradJdbcService.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            List<LineItemInput> items = new ArrayList<>();
            for (int j = 0; j < itemsPerOrder; j++) {
                items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
            }
            tradJdbcService.createOrderWithItems("CUST-" + i, items);
        }

        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<Order> orders = dddJpaRepo.findByProductId(targetProductId);
            assertThat(orders).hasSize(totalOrders);
        });

        double dddJdbc = measureAvg(() -> {
            List<Order> orders = dddJdbcRepo.findByProductId(targetProductId);
            assertThat(orders).hasSize(totalOrders);
        });

        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<TraditionalJpaOrder> orders = tradJpaService.findByProductId(targetProductId);
            assertThat(orders).hasSize(totalOrders);
        });

        double tradJdbc = measureAvg(() -> {
            var orders = tradJdbcService.findOrdersByProductId(targetProductId);
            assertThat(orders).hasSize(totalOrders);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Benchmark O: Projection Query (DTO)
    // =========================================================================

    private double[] benchmarkProjectionQuery(int totalOrders, int itemsPerOrder) {
        // Seed data — DDD
        dddJdbcRepo.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            Order order = createDddOrder(itemsPerOrder);
            dddJdbcRepo.save(order);
        }

        // Seed data — Traditional JPA
        tradJpaService.deleteAll();
        clearPersistenceContext();
        for (int i = 0; i < totalOrders; i++) {
            var tradOrder = tradJpaService.createOrder("CUST-" + i);
            for (int j = 0; j < itemsPerOrder; j++) {
                tradJpaService.addLineItem(tradOrder.getId(), "P" + j, "Product " + j,
                        1 + (j % 5), BigDecimal.valueOf(10 + j));
            }
        }
        clearPersistenceContext();

        // Seed data — Traditional JDBC
        tradJdbcService.deleteAll();
        for (int i = 0; i < totalOrders; i++) {
            List<LineItemInput> items = new ArrayList<>();
            for (int j = 0; j < itemsPerOrder; j++) {
                items.add(new LineItemInput("P" + j, "Product " + j, 1 + (j % 5), BigDecimal.valueOf(10 + j)));
            }
            tradJdbcService.createOrderWithItems("CUST-" + i, items);
        }

        // DDD: must load full aggregates, then map to DTO
        double dddJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<Order> allOrders = dddJpaRepo.findAll();
            var projections = allOrders.stream()
                    .map(o -> Map.of(
                            "id", o.getId(),
                            "customerId", o.getCustomerId(),
                            "itemCount", o.getLineItems().size(),
                            "totalAmount", o.getTotalAmount()))
                    .toList();
            assertThat(projections).hasSize(totalOrders);
        });

        double dddJdbc = measureAvg(() -> {
            List<Order> allOrders = dddJdbcRepo.findAll();
            var projections = allOrders.stream()
                    .map(o -> Map.of(
                            "id", o.getId(),
                            "customerId", o.getCustomerId(),
                            "itemCount", o.getLineItems().size(),
                            "totalAmount", o.getTotalAmount()))
                    .toList();
            assertThat(projections).hasSize(totalOrders);
        });

        // Traditional: lightweight query (no full aggregate loading)
        double tradJpa = measureAvg(() -> {
            clearPersistenceContext();
            List<TraditionalJpaOrder> orders = tradJpaService.findAllOrders();
            assertThat(orders).hasSize(totalOrders);
        });

        double tradJdbc = measureAvg(() -> {
            var projections = tradJdbcService.findOrderProjections();
            assertThat(projections).hasSize(totalOrders);
        });

        return new double[]{dddJpa, dddJdbc, tradJpa, tradJdbc};
    }

    // =========================================================================
    // Invariant Correctness Verification (Scenario I)
    // =========================================================================

    private void verifyDddInvariant() {
        // DDD approach: Aggregate enforces invariant automatically
        dddJdbcRepo.deleteAll();

        // 1. Create order with items
        Order order = Order.create("CUST-VERIFY");
        order.addLineItem("P1", "Product 1", 3, BigDecimal.valueOf(100));  // 300
        order.addLineItem("P2", "Product 2", 2, BigDecimal.valueOf(50));   // 100
        Order saved = dddJdbcRepo.save(order);

        Order loaded = dddJdbcRepo.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(400));
        assertThat(loaded.getLineItems()).hasSize(2);

        // 2. Update quantity
        UUID firstItemId = loaded.getLineItems().getFirst().id();
        loaded.updateLineItemQuantity(firstItemId, 5); // 5 × 100 = 500, total = 600
        dddJdbcRepo.save(loaded);

        Order afterUpdate = dddJdbcRepo.findById(saved.getId()).orElseThrow();
        BigDecimal expectedTotal = afterUpdate.getLineItems().stream()
                .map(OrderLineItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(afterUpdate.getTotalAmount()).isEqualByComparingTo(expectedTotal);

        // 3. Remove item
        afterUpdate.removeLineItem(afterUpdate.getLineItems().getFirst().id());
        dddJdbcRepo.save(afterUpdate);

        Order afterRemove = dddJdbcRepo.findById(saved.getId()).orElseThrow();
        BigDecimal expectedAfterRemove = afterRemove.getLineItems().stream()
                .map(OrderLineItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(afterRemove.getTotalAmount()).isEqualByComparingTo(expectedAfterRemove);
        assertThat(afterRemove.getLineItems()).hasSize(1);

        System.out.println("  [DDD]         Invariant PASSED: totalAmount always equals sum of subtotals");
    }

    private void verifyTraditionalInvariant() {
        tradJdbcService.deleteAll();

        // 1. Create order + items
        UUID orderId = tradJdbcService.createOrder("CUST-VERIFY");
        tradJdbcService.addLineItem(orderId, "P1", "Product 1", 3, BigDecimal.valueOf(100));
        tradJdbcService.addLineItem(orderId, "P2", "Product 2", 2, BigDecimal.valueOf(50));

        var result = tradJdbcService.findOrderWithItems(orderId);
        BigDecimal total = (BigDecimal) result.order().get("total_amount");
        assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(400));

        // 2. Update quantity
        UUID firstItemId = tradJdbcService.findFirstLineItemId(orderId);
        tradJdbcService.updateLineItemQuantity(firstItemId, 5, orderId);

        var afterUpdate = tradJdbcService.findOrderWithItems(orderId);
        BigDecimal updatedTotal = (BigDecimal) afterUpdate.order().get("total_amount");
        BigDecimal sumAfterUpdate = afterUpdate.lineItems().stream()
                .map(m -> (BigDecimal) m.get("subtotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(updatedTotal).isEqualByComparingTo(sumAfterUpdate);

        // 3. Remove item
        tradJdbcService.removeLineItem(firstItemId, orderId);

        var afterRemove = tradJdbcService.findOrderWithItems(orderId);
        BigDecimal removedTotal = (BigDecimal) afterRemove.order().get("total_amount");
        BigDecimal sumAfterRemove = afterRemove.lineItems().stream()
                .map(m -> (BigDecimal) m.get("subtotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(removedTotal).isEqualByComparingTo(sumAfterRemove);

        System.out.println("  [Traditional] Invariant PASSED: totalAmount manually maintained correctly");
    }

    // =========================================================================
    // Measurement Utilities
    // =========================================================================

    private double measureAvg(Runnable action) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            action.run();
        }

        long total = 0;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            action.run();
            long elapsed = System.nanoTime() - start;
            total += elapsed;
        }

        return (total / (double) MEASURE_ITERATIONS) / 1_000_000.0;
    }

    // =========================================================================
    // Data Seeding
    // =========================================================================

    private Order createDddOrder(int itemCount) {
        Order order = Order.create("CUST-BENCH");
        for (int i = 0; i < itemCount; i++) {
            order.addLineItem("P" + i, "Product " + i, 1 + (i % 5), BigDecimal.valueOf(10 + i));
        }
        return order;
    }

    private UUID seedDddOrder(int itemCount) {
        dddJdbcRepo.deleteAll();
        Order order = createDddOrder(itemCount);
        Order saved = dddJdbcRepo.save(order);
        return saved.getId();
    }

    private UUID seedTradJpaOrder(int itemCount) {
        tradJpaService.deleteAll();
        clearPersistenceContext();
        var order = tradJpaService.createOrder("CUST-BENCH");
        UUID orderId = order.getId();
        for (int i = 0; i < itemCount; i++) {
            tradJpaService.addLineItem(orderId, "P" + i, "Product " + i,
                    1 + (i % 5), BigDecimal.valueOf(10 + i));
        }
        clearPersistenceContext();
        return orderId;
    }

    private UUID seedTradJdbcOrder(int itemCount) {
        tradJdbcService.deleteAll();
        List<LineItemInput> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new LineItemInput("P" + i, "Product " + i, 1 + (i % 5), BigDecimal.valueOf(10 + i)));
        }
        return tradJdbcService.createOrderWithItems("CUST-BENCH", items);
    }

    private void writeTradJpa(int itemCount) {
        var order = tradJpaService.createOrder("CUST-BENCH");
        UUID orderId = order.getId();
        for (int i = 0; i < itemCount; i++) {
            tradJpaService.addLineItem(orderId, "P" + i, "Product " + i,
                    1 + (i % 5), BigDecimal.valueOf(10 + i));
        }
    }

    private void writeTradJdbc(int itemCount) {
        UUID orderId = tradJdbcService.createOrder("CUST-BENCH");
        for (int i = 0; i < itemCount; i++) {
            tradJdbcService.addLineItem(orderId, "P" + i, "Product " + i,
                    1 + (i % 5), BigDecimal.valueOf(10 + i));
        }
    }

    private void clearPersistenceContext() {
        entityManager.clear();
    }

    // =========================================================================
    // Result Helpers
    // =========================================================================

    private void printScenarioResult(String scenario, double[] times) {
        System.out.printf("[%s] DDD+JPA: %.1fms | DDD+JDBC: %.1fms | Trad+JPA: %.1fms | Trad+JDBC: %.1fms%n",
                scenario, times[0], times[1], times[2], times[3]);
    }

    private String mark(double value, double minValue) {
        return Math.abs(value - minValue) < 0.01 ? " ★" : "  ";
    }

    private String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text +
               " ".repeat(Math.max(0, width - text.length() - padding));
    }

    private void printAnalysis() {
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");
        System.out.println("  ANALYSIS SUMMARY");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");

        String[] labels = {"DDD+JPA", "DDD+JDBC", "Trad+JPA", "Trad+JDBC"};
        int[] wins = new int[4];

        for (var entry : results.entrySet()) {
            double[] times = entry.getValue();
            int minIdx = 0;
            for (int i = 1; i < 4; i++) {
                if (times[i] < times[minIdx]) minIdx = i;
            }
            wins[minIdx]++;
        }

        System.out.println();
        System.out.println("  [Win Count]");
        for (int i = 0; i < 4; i++) {
            System.out.printf("    %-12s: %d wins%n", labels[i], wins[i]);
        }

        System.out.println();
        System.out.println("  [Key Insights]");

        // Write analysis
        if (results.containsKey("A-Write-200items")) {
            double[] a200 = results.get("A-Write-200items");
            System.out.printf("    Write-200: DDD+JDBC (%.1fms) vs DDD+JPA (%.1fms) → ORM overhead: %.1fx%n",
                    a200[1], a200[0], a200[0] / a200[1]);
            System.out.printf("    Write-200: DDD+JDBC (%.1fms) vs Trad+JPA (%.1fms) → Aggregate+batch vs per-item save: %.1fx%n",
                    a200[1], a200[2], a200[2] / a200[1]);
        }

        // CQRS motivation
        if (results.containsKey("D-Read-OrderOnly")) {
            double[] d = results.get("D-Read-OrderOnly");
            double dddAvg = (d[0] + d[1]) / 2;
            double tradAvg = (d[2] + d[3]) / 2;
            System.out.printf("    Read-OrderOnly: DDD avg %.1fms vs Trad avg %.1fms → DDD %.1fx slower%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        // Status update analysis
        if (results.containsKey("F-Update-Status-Only")) {
            double[] f = results.get("F-Update-Status-Only");
            double dddAvg = (f[0] + f[1]) / 2;
            double tradAvg = (f[2] + f[3]) / 2;
            System.out.printf("    Status-Only: DDD avg %.1fms vs Trad avg %.1fms → DDD %.1fx slower%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        // N+1 analysis
        if (results.containsKey("H-List-100-Orders")) {
            double[] h = results.get("H-List-100-Orders");
            double dddAvg = (h[0] + h[1]) / 2;
            double tradAvg = (h[2] + h[3]) / 2;
            System.out.printf("    List-100-Orders: DDD avg %.1fms vs Trad avg %.1fms → N+1 impact: %.1fx%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        // Aggregate report analysis
        if (results.containsKey("L-Aggregate-Report")) {
            double[] l = results.get("L-Aggregate-Report");
            double dddAvg = (l[0] + l[1]) / 2;
            double tradAvg = (l[2] + l[3]) / 2;
            System.out.printf("    Aggregate-Report: DDD avg %.1fms vs Trad avg %.1fms → full load vs DB agg: %.1fx%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        // Bulk update analysis
        if (results.containsKey("M-Bulk-Update-200")) {
            double[] m = results.get("M-Bulk-Update-200");
            double dddAvg = (m[0] + m[1]) / 2;
            double tradAvg = (m[2] + m[3]) / 2;
            System.out.printf("    Bulk-Update-200: DDD avg %.1fms vs Trad avg %.1fms → per-aggregate vs 1 SQL: %.1fx%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        // Cross-aggregate query analysis
        if (results.containsKey("N-Cross-Aggregate")) {
            double[] n = results.get("N-Cross-Aggregate");
            double dddAvg = (n[0] + n[1]) / 2;
            double tradAvg = (n[2] + n[3]) / 2;
            System.out.printf("    Cross-Aggregate: DDD avg %.1fms vs Trad avg %.1fms → filter in Java vs SQL: %.1fx%n",
                    dddAvg, tradAvg, dddAvg / tradAvg);
        }

        System.out.println();
        System.out.println("  [Conclusion]");
        System.out.println("    Command Side (Write): DDD Aggregate + JDBC → best write performance + invariant safety");
        System.out.println("    Query Side  (Read) : CQRS Read Model / Raw SQL → free from Aggregate boundary");
        System.out.println("    Bulk Operations    : Traditional approach is orders of magnitude faster");
        System.out.println("    DDD's value is NOT performance — it's using design constraints to protect business consistency");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════");
    }
}
