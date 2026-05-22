# Fix Kernel Parity and 2.5M% Gain Bug Plan

## Findings
1. **Index Mismatch**: The GPU kernel was using `maStart` instead of `volStart` in the volatility prefix sum subtraction, causing constant maxed-out volatility scores.
2. **Averaging Fallacy**: The GPU was averaging "Total Return" instead of "Daily Yield", then multiplying by 252. A capped 10,000% trade resulted in 2.52M% yearly gain.
3. **ML Collection Block**: The GPU-biased "fake winner" had parameters that didn't trigger trades on the CPU, resulting in zero ML samples during the final pass.

## Implementation Steps

### 1. Fix Kernel Indexing and Unit Parity
- **`TornadoVmOptimizer.java`**:
  - Fix `avgPriceVol` and `avgSqPriceVol` subtraction indices (ensure `volStart - 1`).
  - Update `rawGain` to use the same logic as CPU (no `+ 0.01f` unless CPU also has it, or add it to both).
  - Lower the outlier cap from 10,000% to 500% to prevent metric explosion.
  - **CRITICAL**: Change `sumTotalProfit` to accumulate `rawGain / duration` (Daily Yield).

### 2. Standardize Yearly Gain Math
- **`TornadoVmOptimizer.java`**:
  - The host aggregation already does `totalProfitAccum / totalTrades`. If `totalProfitAccum` is now `Sum(Daily Yield)`, then `avgDailyGain` will be correct.
- **`Simulation.java`**:
  - Ensure `yearlyGain` is calculated as `Average(Trade Gain / Duration) * 252`.

### 3. Ensure ML Collection
- **`CpuParamOptimizer.java`**:
  - Add a fallback trade density check to ensure we collect *some* samples even if the "winner" is restrictive.
  - Or, simply fix the parity bugs so the CPU finds the same trades as the GPU.

### 4. Verification
- Run `mvn test -Dtest=TornadoVmOptimizerTest#testSingleCandidateParity` and ensure the scores are within 1% tolerance.
- Run E2E and verify "Training on X samples" shows X > 0.
