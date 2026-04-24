# Design Spec: High-Performance Hydration Cache and Early-Exit Mechanics

## Objective
Implement a persistent hydration cache using the H2 database and introduce aggressive mathematical early-exit mechanisms to optimize both data acquisition and parameter search runtime.

## 1. H2 Hydration Cache

### Architecture
We will introduce a caching layer in the `backend` using Spring Data JPA and H2. This cache will store fully hydrated `StockGraphState` objects to avoid redundant API calls to Bridgewise and Koyfin.

### Components
- **`StockCacheEntity`**: A JPA entity mapping to a `STOCK_CACHE` table.
  - `ticker` (String, PK): The unique ticker symbol.
  - `lastUpdated` (LocalDate): The date the data was fetched.
  - `graphDataJson` (CLOB): Serialized `StockGraphState` record.
- **`StockCacheRepository`**: A standard JpaRepository for basic CRUD operations.
- **`StockDataService` Integration**: 
  - Before fetching data from external APIs, the service will check the repository for a valid entry (ticker exists AND `lastUpdated == today`).
  - If a hit occurs, the JSON is deserialized using Jackson/Gson and returned.
  - If a miss occurs, the standard API hydration flow is triggered, and the result is saved to the cache.

### Invalidation Logic
- **Simple Date Check**: Cache entries are considered invalid if their `lastUpdated` timestamp is older than the current calendar day.

---

## 2. Early-Exit Mechanics (Search Optimization)

### Layer 1: Heuristic Short-Circuiting (`Simulation.calculateFastScore`)
The core simulation loop evaluates millions of "stock-days". We can skip the majority of calculations for days that won't result in a trade.

- **Threshold**: 0.65
- **Weights**: Use the current `ScoringWeights`.
- **Mechanism**:
  1. Calculate the feature with the highest weight (e.g., MA Gap).
  2. Calculate the "Current Score" so far.
  3. Calculate "Max Potential Score" = `Current Score` + `Sum(Remaining Weights)`.
  4. If `Max Potential Score < 0.65`, exit immediately and return `0.0`.
  5. Repeat after each major feature calculation.

### Layer 2: Simulation Pruning (`ParamOptimizer.evaluate`)
Some parameter sets in the optimization population will be naturally "bad". We should stop evaluating them as soon as their failure is statistically likely.

- **Mechanism**:
  - After processing a fixed percentage of the stock universe (e.g., first 25% of stocks), check the running average simulation score.
  - If the score is significantly negative (e.g., `< -20.0`), terminate the simulation for the remaining 75% of stocks.
  - Return a penalty score to ensure this parameter set is not selected for the next generation.

---

## 3. Data Flow
1. **Hydration Phase**: `Pipeline` -> `StockDataService` -> `StockCacheRepository` (Check H2) -> (If Miss) External APIs -> Save to H2.
2. **Optimization Phase**: `ParamOptimizer` -> `evaluate()` -> (If Strategy Failing) Early Exit -> `calculateFastScore()` -> (If Day Non-Viable) Early Exit.

## 4. Verification Plan
- **Cache Verification**: Run analysis twice. The second run should show zero "starting fetch for..." logs for cached tickers and complete hydration in seconds.
- **Search Verification**: Monitor "Generation" logs. Generations with bad parameters should complete significantly faster than high-scoring generations.
- **Accuracy Check**: Ensure that the "Top Recommendations" remain consistent with and without the early-exit logic enabled.
