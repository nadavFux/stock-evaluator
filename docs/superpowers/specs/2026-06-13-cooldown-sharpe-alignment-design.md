# Design Spec: Cooldown Days & Sharpe Score Alignment

## 1. Overview
This specification addresses two key issues in the trading parameter optimization pipeline:
1. **Sharpe Score Domination Bug (Approach 1):** The unnormalized trade volume tie-breaker `trades * 0.000001` on the GPU scales up linearly in large runs to dominate the actual Sharpe ratio, rewarding the optimizer for choosing high-frequency losing strategies.
2. **Falling Knife Loop Bug (Approach 2):** When a stock crashes, the optimizer immediately re-buys it day-after-day because it fits moving average gap/reversion criteria, racking up repeated slippage and trading losses. We will introduce a `cooldownDays` parameter to prevent immediate re-entry.

---

## 2. Configuration & Parameter Updates

### 2.1 SimulationRangeConfig
We add a new range list to the JSON/YAML mapping class:
```java
public List<Integer> cooldownDays;
```
If not specified or empty, the defaults in the optimizer will fall back to `5` days.

### 2.2 SimulationParams
We add `int cooldownDays` to the parameter record and update the Builder:
```java
public record SimulationParams(
    ...
    int cooldownDays,
    ...
)
```

### 2.3 Parameter Randomization (CpuParamOptimizer)
We randomize `cooldownDays` using the generation radius `r`:
```java
clampInt(c.cooldownDays() + randInt((int) (5 * r)), 1, 20)
```

---

## 3. Parity Threshold & Score Corrections

### 3.1 Removing the Tie-Breaker
We remove `+ (trades * 0.000001)` from the scoring function in `TornadoVmOptimizer.calculateCandidateScore()` to align with `Simulation.calculateScore()`.

### 3.2 Aligning Trade Density Thresholds
Both CPU and GPU will calculate `minRequiredTrades` dynamically based on the active evaluation subset and grid task count:
```java
double minRequiredTrades = Math.max(5, (double) subsetSize * gridCount / 100.0);
```
If `trades < minRequiredTrades || totalHoldingDays < minRequiredTrades * 2.0`, both CPU and GPU will return `-100.0`.

---

## 4. Trading Cooldown Implementation

### 4.1 CPU Simulation (Simulation.java)
We enforce a cooldown constraint inside `simulateStock`:
```java
int cooldownUntil = -1;
for (int i = timeStart; i < searchLimit; i++) {
    if (i < cooldownUntil) continue;
    if (sim.calculateHeuristic(pkg, sIdx, i) > sim.params.buyThreshold()) {
        ...
        // Hold period simulation
        for (int j = 1; j < absoluteLimit - i; j++) {
            ...
            if (price < (highestPrice * sim.params.sellCutOffPerc()) || (curr == absoluteLimit - 1)) {
                sim.recordTrade((price - buyPrice) / buyPrice, j);
                cooldownUntil = curr + sim.params.cooldownDays() + 1; // Enforce cooldown
                i = curr;
                break;
            }
        }
    }
}
```

### 4.2 GPU Unified Kernel (TornadoVmOptimizer.java)
We update `PARAMETER_STRIDE` to `25` and append the new parameter at index `24`.
Inside the kernel:
```java
int cooldownDays = (int) parameterMatrix.get(paramBase + 24);
int cooldownUntilDay = 0;

for (int d = simStart; d < finalLimit; d++) {
    ...
    int isCooldowned = (d < cooldownUntilDay) ? 1 : 0;
    ...
    int doBuy = condBuy1 * condBuy2 * condBuy3 * isThreadActive * (1 - isCooldowned);
    ...
    cooldownUntilDay = doSell * (d + cooldownDays + 1) + (1 - doSell) * cooldownUntilDay;
}
```
