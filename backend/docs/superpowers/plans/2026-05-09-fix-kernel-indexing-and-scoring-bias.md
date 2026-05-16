# Fix Kernel Indexing and Scoring Bias Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve identical optimization scores and the "leaning into buying" bias by fixing kernel indexing typos and dampening the reward function.

**Architecture:**
- **Kernel Fixes**: Correct indexing errors in `unifiedKernel` that used the wrong lookback windows for volatility.
- **Scoring Dampening**: Use `log1p` for gain potential and reduce trade density targets to prioritize quality over raw volume.
- **State Reset**: Clear plan caches and zero-out buffers to ensure each optimization generation uses fresh data and parameters.

**Tech Stack:** Java 21, TornadoVM (OpenCL), JUnit 5

---

### Task 1: Fix Kernel Indexing Typos

**Files:**
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Fix volatility indexing typos**

In `unifiedKernel`, update the `avgPriceVol` and `avgSqPriceVol` calculations to use `volStart` instead of `maStart` for subtraction.

```java
// TornadoVmOptimizer.java:375
float avgPriceVol = (technicalData.get(dayOffset + 1) - (volStart > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (volStart - 1)) * TECH_DATA_STRIDE + 1) : 0.0f)) / (volCount > 0 ? volCount : 1.0f);
float avgSqPriceVol = (technicalData.get(dayOffset + 2) - (volStart > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (volStart - 1)) * TECH_DATA_STRIDE + 2) : 0.0f)) / (volCount > 0 ? volCount : 1.0f);
```

### Task 2: Harmonize and Dampen Scoring Logic

**Files:**
- Modify: `src/main/java/com/stock/analyzer/core/Simulation.java`
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Dampen Gain Potential and Relax Density in Simulation.java**

Update `calculateScore` to use a more conservative density target (2% instead of 10%) and ensure `log1p` dampening is robust.

```java
// Simulation.java:231
double densityFactor = Math.min(1.0, ((double) tradeCount / Math.max(1.0, totalEvaluations)) / 0.02); // 2% target
// ...
double gainPotential = Math.log1p(Math.max(0, yearlyGain / 100.0)) * 2.0; // Scale log contribution
return sharpe * volumeMultiplier * durationMultiplier * (1.0 + gainPotential);
```

- [ ] **Step 2: Sync and Dampen Scoring in TornadoVmOptimizer.java**

Update `calculateCandidateScore` to match `Simulation.java` exactly, including the `log1p` fix and density relax.

```java
// TornadoVmOptimizer.java:247
double densityFactor = Math.min(1.0, (trades / (float) (subsetSize * gridCount)) / 0.02); // 2% target
// ...
double gainPotential = Math.log1p(Math.max(0, yearlyGain / 100.0)) * 2.0;
return sharpeRatio * volumeConsistency * durationMultiplier * (1.0 + gainPotential);
```

### Task 3: Ensure Buffer and Plan Freshness

**Files:**
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Clear plan cache on optimize() entry**

```java
@Override
public SimulationParams optimize(List<StockGraphState> allStocks) {
    logger.info("Starting GPU Multi-Start Optimization...");
    planCache.clear(); // Force re-transfer of FIRST_EXECUTION buffers
    // ...
```

- [ ] **Step 2: Zero-out results buffer on host before each batch**

```java
// TornadoVmOptimizer.java:194
for (int i = 0; i < currentBatchSize * subsetSize * gridCount * OPTIMIZATION_RESULT_STRIDE; i++) {
    optimizationResults.set(i, 0.0f);
}
plan.execute();
```

### Task 4: Verification

**Files:**
- Modify: `src/test/java/com/stock/analyzer/service/TornadoVmOptimizerTest.java`

- [ ] **Step 1: Update parity test to assert non-zero, varied scores**

```java
@Test
public void testCandidateDiversity() {
    // Run two candidates and assert their results are different
    // ... (implementation in task)
}
```

- [ ] **Step 2: Run all tests**

Run: `mvn test -Dtest=TornadoVmOptimizerTest,SimulationGainTest`
Expected: PASS
