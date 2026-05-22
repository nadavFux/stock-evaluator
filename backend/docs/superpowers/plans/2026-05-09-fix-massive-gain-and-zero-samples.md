# Fix Massive Gain and Zero ML Samples Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the astronomical ~2.5M% yearly gain calculation and resolve the issue of zero ML samples being collected after optimization.

**Architecture:**
- **Gain Calculation Fix**: Standardize the annualization formula across CPU and GPU paths to prevent linear extrapolation explosion. Use a realistic "Portfolio Growth" metric or cap the daily yields.
- **ML Sample Collection Fix**: Ensure `TornadoVmOptimizer` correctly triggers `collectML` mode during its final "global winner" validation pass using the fallback CPU path.
- **Unit Normalization**: Ensure all price-based indicators are correctly scaled (percentage vs raw) in the GPU kernel.

**Tech Stack:** Java 21, TornadoVM, DJL (Deep Java Library)

---

### Task 1: Fix Yearly Gain Extrapolation

**Files:**
- Modify: `src/main/java/com/stock/analyzer/core/Simulation.java`
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Cap daily yields and standardize annualization in Simulation.java**

The current formula `Average(Gain% / Duration) * 252` is highly sensitive to outliers. Cap the `dailyGain` and ensure the denominator is total simulation time to represent a realistic portfolio.

```java
// Simulation.java:220
double totalAlpha = 0;
for (int i = 0; i < tradeCount; i++) {
    double dailyYield = (gains[i] * 100.0) / Math.max(1, holdDays[i]);
    totalAlpha += Math.min(2.0, dailyYield); // Cap daily yield at 2% for stability
}
this.yearlyGain = (totalAlpha / tradeCount) * 252.0;
```

- [ ] **Step 2: Sync GPU annualization in TornadoVmOptimizer.java**

Ensure the GPU path uses the same capping and averaging logic.

```java
// TornadoVmOptimizer.java:225
double avgDailyGain = totalTrades > 0 ? totalProfitAccum / totalTrades : 0;
avgDailyGain = Math.min(2.0, avgDailyGain); // Match CPU capping
double yearlyGain = avgDailyGain * 252.0;
```

### Task 2: Fix ML Sample Collection Path

**Files:**
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Enable ML collection in the final winner validation**

The `TornadoVmOptimizer.optimize()` method calls `fallback.evaluateCandidate()` at the end. We must ensure this call actually triggers the `simulateStock` loop with `collectML = true`.

```java
// TornadoVmOptimizer.java:158
logger.info("Selected Global Winner with score: {}", globalWinner.score());
// Use evaluateCandidate with collectML = true to populate mlService samples
fallback.evaluateCandidate(globalWinner.params(), dataPkg, true); 
```

### Task 3: Kernel Robustness and Normalization

**Files:**
- Modify: `src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Add guards against zero duration and invalid prices in kernel**

```java
// TornadoVmOptimizer.java:405
float duration = (float) d - entryDay;
if (duration > 0.5f) { // Ensure at least 1 day or meaningful intra-day
    float rawGain = (price - entryPrice) / (entryPrice + 0.001f) * 100.0f;
    float dailyGain = Math.min(2.0f, rawGain / duration); // Cap in kernel too
    // ...
```

### Task 4: Verification

- [ ] **Step 1: Run E2E test and verify sample count**

Run: `mvn test -Dtest=TornadoVmOptimizerTest#testFullPipelineE2E`
Verify: Logs should show "Training Quantile LSTM on X samples..." where X > 0.
Verify: Yearly gain should be in a realistic range (< 1000%).

- [ ] **Step 2: Verify sample collection in logs**

Add a temporary log in `MLModelService.collectSample` to verify samples are hitting the service during the final pass.
