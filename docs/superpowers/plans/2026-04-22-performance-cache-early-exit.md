# Performance Cache and Early-Exit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a persistent hydration cache in H2 and aggressive pruning logic to speed up stock hydration and parameter optimization.

**Architecture:** Use Spring Data JPA for H2 persistence and mathematical "theoretical maximum" logic for pruning non-viable stock-days and parameter sets.

**Tech Stack:** Java, Spring Boot, Spring Data JPA, H2, GSON.

---

### Task 1: H2 Stock Hydration Cache

**Files:**
- Create: `backend/src/main/java/com/stock/analyzer/model/StockCacheEntity.java`
- Create: `backend/src/main/java/com/stock/analyzer/infra/StockCacheRepository.java`
- Modify: `backend/src/main/java/com/stock/analyzer/service/StockDataService.java`
- Modify: `backend/src/main/java/com/stock/analyzer/infra/ServiceConfig.java`

- [ ] **Step 1: Create the StockCacheEntity**
```java
package com.stock.analyzer.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "STOCK_CACHE")
public class StockCacheEntity {
    @Id
    private String ticker;
    private LocalDate lastUpdated;
    @Lob
    @Column(columnDefinition = "CLOB")
    private String graphDataJson;

    public StockCacheEntity() {}
    public StockCacheEntity(String ticker, LocalDate lastUpdated, String graphDataJson) {
        this.ticker = ticker;
        this.lastUpdated = lastUpdated;
        this.graphDataJson = graphDataJson;
    }
    public String getTicker() { return ticker; }
    public LocalDate getLastUpdated() { return lastUpdated; }
    public String getGraphDataJson() { return graphDataJson; }
}
```

- [ ] **Step 2: Create the StockCacheRepository**
```java
package com.stock.analyzer.infra;

import com.stock.analyzer.model.StockCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockCacheRepository extends JpaRepository<StockCacheEntity, String> {
}
```

- [ ] **Step 3: Update ServiceConfig to inject Repository**
```java
    @Bean
    public StockDataService stockDataService(HttpClientService httpClientService, com.stock.analyzer.infra.StockCacheRepository cacheRepository) {
        Map<String, String> authHeaders = new HashMap<>();
        authHeaders.put("api-key", "6a3a617f15f02e5302b849d18123bb5a32b3b0154ad2a3ddf55e7b5f66e39132");
        authHeaders.put("date-format", "epoch");
        authHeaders.put("Referer", "https://plus.tase.co.il/");

        return new StockDataService(httpClientService,
                "https://api.bridgewise.com/v2/scanner?n=4000&gics={code}&last_n_days=1000&raw=true&metadata=true&score=true&price_equity=true&language=he-IL",
                "https://apipa.tase.co.il/tr/assets/",
                "https://api.bridgewise.com/v2/technical-analysis?identifier={id}&summary=true&language=he-IL&short_name=true",
                authHeaders,
                cacheRepository);
    }
```

- [ ] **Step 4: Integrate Cache into StockDataService**
```java
    private final StockCacheRepository cacheRepository;
    private final Gson gson = new Gson();

    public StockDataService(HttpClientService httpClient, String scannerUrl, String identifierUrl, String techUrl, Map<String, String> authHeaders, StockCacheRepository cacheRepository) {
        this.httpClient = httpClient;
        this.scannerUrl = scannerUrl;
        this.identifierUrl = identifierUrl;
        this.techUrl = techUrl;
        this.authHeaders = authHeaders;
        this.cacheRepository = cacheRepository;
    }

    public Stock enrich(BaseStock base) {
        String ticker = base.ticker_symbol();
        var cached = cacheRepository.findById(ticker);
        if (cached.isPresent() && cached.get().getLastUpdated().equals(LocalDate.now())) {
            try {
                return gson.fromJson(cached.get().getGraphDataJson(), Stock.class);
            } catch (Exception e) {
                logger.warn("Failed to deserialize cache for {}, fetching fresh.", ticker);
            }
        }

        // ... existing fetch logic ...
        // After fetching 'enriched':
        if (enriched != null) {
            cacheRepository.save(new StockCacheEntity(ticker, LocalDate.now(), gson.toJson(enriched)));
        }
        return enriched;
    }
```

- [ ] **Step 5: Commit**
```bash
git add .
git commit -m "feat: implement H2 persistent hydration cache"
```

---

### Task 2: Heuristic Early-Exit Pruning

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/core/Simulation.java`

- [ ] **Step 1: Implement Pruning Logic in calculateFastScore**
```java
        // 1. maScore calculation (Inlined normalize)
        double maMin = params.lowerPriceToLongAvgBuyIn();
        double maMax = params.higherPriceToLongAvgBuyIn();
        double maScore = 1.0 - (maMax == maMin ? 1.0 : Math.max(0.0, Math.min(1.0, (maGap - maMin) / (maMax - maMin))));
        
        // --- PRUNING START ---
        double currentScoreSoFar = maScore * weights.movingAvgGapWeight();
        double remainingWeight = weights.reversionToMeanWeight() + weights.ratingWeight() + weights.upwardIncRateWeight() + 
                                 weights.rvolWeight() + weights.pegWeight() + weights.volatilityCompressionWeight();
        
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);
        // --- PRUNING END ---
```

- [ ] **Step 2: Commit**
```bash
git add backend/src/main/java/com/stock/analyzer/core/Simulation.java
git commit -m "perf: add heuristic early-exit pruning in calculateFastScore"
```

---

### Task 3: Statistical Simulation Pruning

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/service/ParamOptimizer.java`

- [ ] **Step 1: Add running average pruning in evaluate()**
```java
    private double evaluate(SimulationParams params, SimulationDataPackage pkg, boolean collectMLData) {
        Simulation simulation = new Simulation(params);
        int count = 0;
        int stocksProcessed = 0;
        int pruningCheckpoint = pkg.stockCount / 4; // Check after 25%

        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, simulation, timeFrame, collectMLData);
                        
                        // Statistical Pruning
                        stocksProcessed++;
                        if (!collectMLData && stocksProcessed == pruningCheckpoint && stocksProcessed > 10) {
                            double currentScore = simulation.calculateSimulationScore();
                            if (currentScore < -20.0) {
                                return -100.0; // Early exit for bad parameter set
                            }
                        }
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        count++;
                    }
                }
            }
        }
        return count > 0 ? simulation.calculateSimulationScore() : -100.0;
    }
```

- [ ] **Step 2: Commit**
```bash
git add backend/src/main/java/com/stock/analyzer/service/ParamOptimizer.java
git commit -m "perf: implement statistical simulation pruning in ParamOptimizer"
```
