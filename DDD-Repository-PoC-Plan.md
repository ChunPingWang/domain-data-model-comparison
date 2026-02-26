# DDD Repository Performance PoC Plan

## 1. Background & Motivation

在 DDD 戰術設計中，Repository 以 **Aggregate** 為單位操作——存取的永遠是 Aggregate Root 而非單獨的子 Entity。這對比傳統 Data Model（每張表對應一個 DAO/Repository）在設計哲學上有根本性差異。

前期討論中我們推論了四種組合的性能特徵：

```
┌──────────────────────┬──────────────────────┬──────────────────────┐
│                      │  DDD Aggregate       │  Traditional         │
│                      │  + Repository        │  Data Model          │
├──────────────────────┼──────────────────────┼──────────────────────┤
│  ORM                 │  Approach 1          │  Approach 3          │
│  (JPA/Hibernate)     │  中等性能，有隱性開銷   │  性能不可預測          │
├──────────────────────┼──────────────────────┼──────────────────────┤
│  Non-ORM             │  Approach 2          │  Approach 4          │
│  (Raw JDBC)          │  高性能，高實作成本     │  最高性能，最低一致性   │
└──────────────────────┴──────────────────────┴──────────────────────┘
```

**本 PoC 的目的是用量化數據驗證這些推論。**

---

## 2. PoC Objectives

| # | 目標 | 驗證假設 |
|---|------|----------|
| O1 | 量化 DDD Aggregate Repository 與傳統 Data Model 的寫入性能差異 | DDD 以 Aggregate 為單位的寫入在小/中/大聚合下的表現 |
| O2 | 量化 ORM (Hibernate) 與非 ORM (Raw JDBC) 的性能差異 | Hibernate dirty checking、entity mapping、N+1 的實際影響 |
| O3 | 驗證「只讀 Order 不需 LineItems」場景下 DDD 的劣勢 | DDD 必須載入完整 Aggregate vs Traditional 可單表查詢 |
| O4 | 驗證 CQRS 的必要性 | 為 Query Side 分離 Read Model 提供數據支持 |
| O5 | 為團隊技術選型提供決策依據 | Command Side vs Query Side 的最佳組合 |

---

## 3. Technical Stack

| 技術 | 版本 | 用途 |
|------|------|------|
| Java | 23 | Runtime |
| Spring Boot | 4.0.3 | Application Framework |
| Spring Data JPA | 4.0.x (Hibernate 7.x) | Approach 1 & 3 的 ORM 層 |
| Spring JDBC (JdbcTemplate) | 7.0.x | Approach 2 & 4 的非 ORM 層 |
| PostgreSQL | 16 (Alpine) | Database，透過 Testcontainers 啟動 |
| Testcontainers | 2.0.2 | 容器化整合測試基礎設施 |
| JUnit 6 (Jupiter) | via Spring Boot 4 | 測試框架 |

---

## 4. Domain Model Design

選用 **Order（訂單）** 作為 Aggregate，貼近金融/零售業務場景：

```
Order (Aggregate Root)
├── id: UUID
├── customerId: String
├── status: OrderStatus
│     DRAFT → SUBMITTED → CONFIRMED → SHIPPED → COMPLETED
│                                              → CANCELLED
├── totalAmount: BigDecimal
│     Invariant: 必須等於 Σ lineItems[i].subtotal
├── lineItems: List<OrderLineItem>    ← Child Entity
│   ├── id: UUID
│   ├── productId: String
│   ├── productName: String
│   ├── quantity: int              (> 0)
│   ├── unitPrice: BigDecimal
│   └── subtotal: BigDecimal       (= quantity × unitPrice)
├── createdAt: LocalDateTime
├── updatedAt: LocalDateTime
└── version: int                   (Optimistic Locking)
```

**選擇 Order 的原因：**

- Aggregate 大小可變（5～200+ LineItems），可模擬不同業務規模
- 有明確的 Invariant（totalAmount = sum of subtotals）
- 有狀態機（status transitions），體現 Domain Behavior
- 在銀行/保險/零售場景中具有高度代表性

---

## 5. Four Approaches in Detail

### Approach 1: DDD Aggregate + JPA/Hibernate

```
Domain Layer           Infrastructure Layer
┌────────────────┐     ┌──────────────────────────┐
│  Order          │     │  JpaOrderEntity           │
│  (Pure Domain)  │ ←→  │  JpaOrderLineItemEntity   │
│  OrderLineItem  │     │  @OneToMany cascade=ALL   │
│  OrderRepository│     │  SpringDataJpaRepository  │
└────────────────┘     └──────────────────────────┘
```

- Domain Model 與 JPA Entity **分離**（Anti-Corruption Layer）
- Repository 實作負責 Domain ↔ JPA Entity 的雙向映射
- Hibernate 負責 cascade、dirty checking、lazy loading
- 使用 `JOIN FETCH` 避免 N+1

**觀察重點：** Hibernate dirty checking 開銷、entity mapping 成本、persistence context 記憶體壓力

### Approach 2: DDD Aggregate + Raw JDBC

```
Domain Layer           Infrastructure Layer
┌────────────────┐     ┌──────────────────────────┐
│  Order          │     │  JdbcTemplate             │
│  (Pure Domain)  │ ←→  │  手動 SQL                  │
│  OrderLineItem  │     │  手動 Aggregate 組裝/拆解   │
│  OrderRepository│     │  Batch Insert             │
└────────────────┘     └──────────────────────────┘
```

- 完全掌控 SQL，使用 PostgreSQL `ON CONFLICT` upsert
- Child Entities 採用「先刪後插」策略（Delete-then-Insert）
- 讀取時精確 2 條 SQL：一條 order、一條 items，手動組裝
- 使用 `batchUpdate` 批次寫入

**觀察重點：** 精確 SQL 的性能優勢、手動組裝的成本

### Approach 3: Traditional Data Model + JPA

```
Service Layer          Repository Layer
┌────────────────┐     ┌──────────────────────────┐
│  OrderService   │     │  TraditionalJpaOrderRepo  │
│  (業務邏輯)     │ ──→ │  TraditionalJpaLineItemRepo│
│  手動維護一致性  │     │  各自獨立操作              │
└────────────────┘     └──────────────────────────┘
```

- Order 和 LineItem 各有獨立的 JPA Repository
- Service 層手動 `recalculateTotal()`
- 每次 `addLineItem` 觸發：save item → query all items → sum → update order
- 沒有 Aggregate 邊界保護

**觀察重點：** 額外的 round-trip 次數、一致性維護的隱性成本

### Approach 4: Traditional Data Model + Raw JDBC

```
Service Layer
┌────────────────────────────────┐
│  TraditionalJdbcOrderService    │
│  手寫所有 SQL                    │
│  用 subquery 更新 total          │
│  無 ORM、無 Aggregate 邊界       │
└────────────────────────────────┘
```

- 最直接、最透明
- 善用 PostgreSQL subquery 在 DB 端完成 total 計算
- 無 entity mapping、無 dirty checking
- 一致性完全靠開發者自行維護

**觀察重點：** 作為性能基線（baseline）

---

## 6. Benchmark Scenarios

### Scenario A — Write Performance（寫入 Order + N LineItems）

| 子場景 | LineItems 數量 | 觀察重點 |
|--------|---------------|----------|
| A-Small | 5 | 小聚合寫入，ORM 開銷佔比 |
| A-Medium | 50 | 中等聚合，batch insert vs 逐筆 save |
| A-Large | 200 | 大聚合，dirty checking 壓力、記憶體開銷 |

**測量方式：** Warmup 3 次 → 測量 10 次取平均值（毫秒）

### Scenario B — Read Full Aggregate（讀取完整聚合）

- 預先用 Raw JDBC 種入 200 筆 LineItems 的 Order
- 四種方式分別讀取，驗證：
  - JPA `JOIN FETCH` vs JDBC 精確 2 SQL
  - Entity mapping / proxy 建立的開銷
  - Traditional 模式需要兩次獨立呼叫

### Scenario C — Batch Throughput（批次建立 500 筆 Orders）

- 每筆 Order 含 5 個 LineItems
- 模擬高吞吐場景（如批次匯入、EOD 處理）
- 觀察 ORM session 在大量操作下的表現

### Scenario D — Read Order Only（只讀 Order，不需 LineItems）

**這是驗證 CQRS 必要性的關鍵場景。**

- DDD Repository 設計必須載入整個 Aggregate（含所有 LineItems）
- Traditional 模式可以只查 Order 表
- 預期 Traditional 有顯著優勢
- 結論指向：Query Side 需要獨立的 Read Model

---

## 7. Data Volume & Configuration

| 參數 | 值 | 說明 |
|------|----|------|
| Small Aggregate | 5 LineItems | 模擬簡單訂單 |
| Medium Aggregate | 50 LineItems | 模擬一般批發訂單 |
| Large Aggregate | 200 LineItems | 模擬大型採購單或保險明細 |
| Batch Count | 500 Orders | 模擬批次處理場景 |
| Warmup Iterations | 3 | JIT 暖機 |
| Measure Iterations | 10 | 取平均消除抖動 |
| DB Connection Pool | max=20, min-idle=5 | HikariCP |
| Hibernate batch_size | 50 | 開啟 batch insert |
| JPA open-in-view | false | 避免 lazy loading 意外 |

---

## 8. Project Structure

```
ddd-repository-poc/
├── pom.xml
├── README.md
├── src/main/java/com/example/poc/
│   ├── DddRepositoryPocApplication.java
│   ├── JpaConfig.java
│   │
│   ├── domain/                          ← Pure Domain Layer
│   │   ├── model/
│   │   │   ├── Order.java               ← Aggregate Root
│   │   │   ├── OrderLineItem.java       ← Child Entity (record)
│   │   │   └── OrderStatus.java         ← Enum
│   │   └── repository/
│   │       └── OrderRepository.java     ← Domain Repository Interface
│   │
│   ├── approach1_ddd_jpa/               ← DDD + JPA/Hibernate
│   │   ├── entity/
│   │   │   ├── JpaOrderEntity.java
│   │   │   └── JpaOrderLineItemEntity.java
│   │   └── repository/
│   │       ├── SpringDataJpaOrderRepository.java
│   │       └── DddJpaOrderRepository.java   ← implements OrderRepository
│   │
│   ├── approach2_ddd_jdbc/              ← DDD + Raw JDBC
│   │   └── repository/
│   │       └── DddJdbcOrderRepository.java  ← implements OrderRepository
│   │
│   ├── approach3_traditional_jpa/       ← Traditional + JPA
│   │   ├── entity/
│   │   │   ├── TraditionalJpaOrder.java
│   │   │   └── TraditionalJpaLineItem.java
│   │   ├── repository/
│   │   │   ├── TraditionalJpaOrderRepo.java
│   │   │   └── TraditionalJpaLineItemRepo.java
│   │   └── service/
│   │       └── TraditionalJpaOrderService.java
│   │
│   └── approach4_traditional_jdbc/      ← Traditional + Raw JDBC
│       └── service/
│           └── TraditionalJdbcOrderService.java
│
├── src/main/resources/
│   ├── application.properties
│   └── schema.sql                       ← 各 Approach 獨立表結構
│
└── src/test/java/com/example/poc/
    └── RepositoryBenchmarkTest.java     ← 完整 Benchmark 測試
```

---

## 9. Database Schema Strategy

為避免 Hibernate 兩個 `@Entity` 映射同一張表的衝突，各 Approach 使用獨立表：

| Approach | Order Table | LineItem Table |
|----------|-------------|----------------|
| 1 (DDD+JPA) | `orders` | `order_line_items` |
| 2 (DDD+JDBC) | `orders` | `order_line_items` |
| 3 (Trad+JPA) | `trad_orders` | `trad_order_line_items` |
| 4 (Trad+JDBC) | `jdbc_orders` | `jdbc_order_line_items` |

所有表結構完全相同，僅名稱不同，確保公平比較。

---

## 10. Expected Benchmark Results（假設驗證）

### 寫入場景 (A/C)

| 預測排名 | Approach | 原因 |
|----------|----------|------|
| 🥇 最快 | DDD+JDBC | Batch insert + 精確 SQL，無 ORM 開銷 |
| 🥈 次快 | Trad+JDBC | 接近，但每筆 item 各自 update total（多次 round-trip）|
| 🥉 中等 | DDD+JPA | Hibernate dirty checking + entity mapping 開銷 |
| 🏅 最慢 | Trad+JPA | 每筆 item 獨立 save + 額外 recalculate 查詢 |

### 讀取完整 Aggregate (B)

| 預測排名 | Approach | 原因 |
|----------|----------|------|
| 🥇 | DDD+JDBC | 精確 2 SQL + 手動組裝 |
| 🥈 | Trad+JDBC | 同為 2 SQL，但結果未組裝成 Domain Object |
| 🥉 | DDD+JPA | JOIN FETCH 一次查詢，但有 entity mapping 成本 |
| 🏅 | Trad+JPA | 兩次獨立 JPA 查詢 + entity proxy 建立 |

### 只讀 Order (D) — CQRS 動機驗證

| 預測排名 | Approach | 原因 |
|----------|----------|------|
| 🥇 | Trad+JDBC | 單條 SELECT，無 mapping |
| 🥈 | Trad+JPA | 單條 SELECT + JPA entity 建立 |
| 🥉 | DDD+JDBC | 必須載入全部 200 筆 LineItems |
| 🏅 | DDD+JPA | 必須載入全 Aggregate + Hibernate overhead |

---

## 11. How to Execute

### Prerequisites

- Java 23+
- Docker Desktop（for Testcontainers）
- Maven 3.9+

### Run Benchmark

```bash
cd ddd-repository-poc
./mvnw test -Dtest=RepositoryBenchmarkTest
```

### Expected Output

測試結束時會印出完整報告表格：

```
╔════════════════════════════════════════════════════════════════════════════╗
║          DDD REPOSITORY PERFORMANCE BENCHMARK — FINAL REPORT              ║
╠════════════════════════════════════════════════════════════════════════════╣
║ Scenario                       │   DDD+JPA │  DDD+JDBC │  Trad+JPA │ Trad+JDBC║
╠════════════════════════════════════════════════════════════════════════════╣
║ A-Write-5items                 │    xx ms  │   xx ms ★ │    xx ms  │   xx ms  ║
║ A-Write-50items                │    xx ms  │   xx ms ★ │    xx ms  │   xx ms  ║
║ ...                                                                        ║
╚════════════════════════════════════════════════════════════════════════════╝
```

---

## 12. Success Criteria

| # | 驗證項目 | 通過條件 |
|---|---------|---------|
| SC1 | 所有四種 Approach 的測試均通過 | 資料正確寫入/讀出，Invariant 成立 |
| SC2 | 寫入場景中 DDD+JDBC 性能優於或接近 Trad+JDBC | 驗證 Aggregate 模式不會犧牲寫入性能 |
| SC3 | 大聚合（200 items）下 JPA 與 JDBC 有明顯差異 | 量化 ORM overhead |
| SC4 | Scenario D 中 Traditional 顯著快於 DDD | 驗證 CQRS Read Model 的必要性 |
| SC5 | 產出可量化的性能對比報告 | 作為架構決策的數據依據 |

---

## 13. Risks & Mitigations

| 風險 | 影響 | 緩解措施 |
|------|------|----------|
| Testcontainers 啟動慢 | 測試執行時間長 | 使用 `@ServiceConnection` 共享容器 |
| JIT 暖機導致前幾次測量不穩定 | 結果失真 | 3 次 warmup 後再測量 |
| Hibernate L1/L2 cache 干擾 | 讀取場景不公平 | 每次讀取前 clear persistence context |
| 容器內 DB 性能與 Production 不同 | 絕對值不代表 Production | 只比較相對排名，不看絕對數值 |
| Spring Boot 4 / Testcontainers 2.0 breaking changes | 編譯失敗 | 已確認最新 artifact 名稱與 API |

---

## 14. Timeline

| Phase | 工作項目 | 時間 |
|-------|---------|------|
| Phase 1 | Project setup + Domain Model + Schema | 已完成 |
| Phase 2 | 四種 Approach 實作 | 已完成 |
| Phase 3 | Benchmark Test 編寫 | 已完成 |
| Phase 4 | 執行 Benchmark + 分析結果 | 0.5 天 |
| Phase 5 | 撰寫結論報告 + 架構建議 | 0.5 天 |

---

## 15. Deliverables

1. **完整可執行的 PoC 專案**（Maven project, `mvnw test` 一鍵執行）
2. **性能對比報告**（Console output + 分析）
3. **架構建議文件**（基於數據的 Command/Query Side 技術選型建議）

---

## 16. Conclusion Preview

基於推論（待數據驗證）：

> **Command Side（寫入）：** DDD Aggregate + JDBC/JOOQ
> — 正確性由 Aggregate invariant 保障，性能由精確 SQL 保障
>
> **Query Side（讀取）：** Raw SQL / CQRS Read Model
> — 不受 Aggregate 邊界限制，可自由 JOIN、projection、denormalize
>
> **如果團隊偏好 ORM：** 考慮 Spring Data JDBC（非 JPA）
> — 無 lazy loading、無 dirty checking、天然以 Aggregate 為單位操作

在金融系統中，**交易正確性的價值遠高於毫秒級的性能差異**。DDD + Repository 的核心價值不在性能，而在於用設計約束來守護業務一致性。
