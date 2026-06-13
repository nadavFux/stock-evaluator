# Cooldown Days & Sharpe Score Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the GPU/CPU optimizer's propensity to over-trade on crashing stocks and remove the unnormalized trade volume score bonus by implementing a configurable trading cooldown and aligned trade density thresholds.

**Architecture:** We will add `cooldownDays` to `SimulationParams` and `SimulationRangeConfig`. We will update the daily simulation loop on both CPU (`Simulation.java`) and GPU (`TornadoVmOptimizer.java` OpenCL kernel) to block trades during a cooldown period after a trade closes. Finally, we will remove the `trades * 0.000001` tie-breaker and align the trade density requirements on both optimizers.

**Tech Stack:** Spring Boot 3.4.0 (Java 21 LTS), TornadoVM 4.0.0, OpenCL.

---

### Task 1: Update Configuration and Parameters

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/model/SimulationRangeConfig.java`
- Modify: `backend/src/main/java/com/stock/analyzer/model/SimulationParams.java`
- Modify: `backend/src/test/java/com/stock/analyzer/core/SimulationTest.java`
- Modify: `backend/src/test/java/com/stock/analyzer/service/CpuParamOptimizerTest.java`
- Modify: `backend/src/test/java/com/stock/analyzer/service/RecommendationValidationTest.java`

- [ ] **Step 1: Add cooldownDays to SimulationRangeConfig**
  Modify [SimulationRangeConfig.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/model/SimulationRangeConfig.java#L55) to add the `cooldownDays` list:
  ```java
  public List<Double> riskFreeRate;
  @NotEmpty
  public List<Integer> sectors;
  @NotEmpty
  public List<String> exchanges;
  @NotNull
  public String outputPath;
  
  // New cooldown parameter range
  public List<Integer> cooldownDays;
  ```

- [ ] **Step 2: Add cooldownDays to SimulationParams**
  Modify [SimulationParams.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/model/SimulationParams.java#L3) to add `int cooldownDays` to the record and its `Builder` class:
  ```java
  public record SimulationParams(
      double sellCutOffPerc,
      double lowerPriceToLongAvgBuyIn,
      double higherPriceToLongAvgBuyIn,
      int timeFrameForUpwardLongAvg,
      double aboveAvgRatingPricePerc,
      int timeFrameForUpwardShortPrice,
      int timeFrameForOscillator,
      double maxRSI,
      double minMarketCap,
      int longMovingAvgTime,
      double minRateOfAvgInc,
      int maxPERatio,
      double minRating,
      double maxRating,
      double maxMarketCap,
      double riskFreeRate,
      double buyThreshold,
      double movingAvgGapWeight,
      double reversionToMeanWeight,
      double ratingWeight,
      double upwardIncRateWeight,
      double rvolWeight,
      double pegWeight,
      double volatilityCompressionWeight,
      int cooldownDays
  ) {
  ```
  And update `SimulationParams.Builder` constructor and build methods:
  ```java
          private int cooldownDays = 5;

          public Builder(SimulationParams other) {
              this.sellCutOffPerc = other.sellCutOffPerc();
              ...
              this.volatilityCompressionWeight = other.volatilityCompressionWeight();
              this.cooldownDays = other.cooldownDays();
          }

          public Builder cooldownDays(int cooldownDays) {
              this.cooldownDays = cooldownDays;
              return this;
          }

          public SimulationParams build() {
              return new SimulationParams(
                  sellCutOffPerc, lowerPriceToLongAvgBuyIn, higherPriceToLongAvgBuyIn,
                  timeFrameForUpwardLongAvg, aboveAvgRatingPricePerc, timeFrameForUpwardShortPrice,
                  timeFrameForOscillator, maxRSI, minMarketCap, longMovingAvgTime,
                  minRateOfAvgInc, maxPERatio, minRating, maxRating, maxMarketCap,
                  riskFreeRate, buyThreshold, movingAvgGapWeight, reversionToMeanWeight,
                  ratingWeight, upwardIncRateWeight, rvolWeight, pegWeight,
                  volatilityCompressionWeight, cooldownDays
              );
          }
  ```

- [ ] **Step 3: Update existing test parameters to include cooldownDays**
  Modify [SimulationTest.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/test/java/com/stock/analyzer/core/SimulationTest.java#L71), [CpuParamOptimizerTest.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/test/java/com/stock/analyzer/service/CpuParamOptimizerTest.java#L61), [RecommendationValidationTest.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/test/java/com/stock/analyzer/service/RecommendationValidationTest.java#L14), and [TornadoVmOptimizerTest.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/test/java/com/stock/analyzer/service/TornadoVmOptimizerTest.java#L71) to include a default cooldown of `5` days in their `new SimulationParams(...)` instantiations. For example:
  ```java
          return new SimulationParams(
              0.95, 0.9, 1.1, 50, 1.05, 20, 10, 70, 0, 20, 0, 100, 1, 5, 1000000000, 0.0, 0.65,
              0.2, 0.15, 0.2, 0.15, 0.1, 0.1, 0.1, 5
          );
  ```

- [ ] **Step 4: Verify Compilation**
  Run: `mvn test-compile`
  Expected: BUILD SUCCESS (meaning all records and test instantiations compile).

- [ ] **Step 5: Commit changes**
  Run: `git commit -am "feat: add cooldownDays to parameters and configurations"`

---

### Task 2: CPU Trading Cooldown Enforcement

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/core/Simulation.java`
- Modify: `backend/src/test/java/com/stock/analyzer/core/SimulationTest.java`

- [ ] **Step 1: Enforce cooldown in CPU simulation loop**
  Modify [Simulation.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/core/Simulation.java#L211)'s `simulateStock()` method:
  ```java
      private void simulateStock(Simulation sim, SimulationDataPackage pkg, int sIdx, int daysBack, int searchTime, int selectTime, boolean ml, Set<String> collectedPoints) {
          int timeStart = Math.max(0, pkg.daysCount - daysBack);
          int searchLimit = Math.min(timeStart + searchTime, pkg.daysCount);
          int absoluteLimit = (selectTime > 0) ? Math.min(timeStart + searchTime + selectTime, pkg.daysCount) : pkg.daysCount;

          sim.addSimulationDays(absoluteLimit - timeStart);
          int cooldownUntil = -1;

          for (int i = timeStart; i < searchLimit; i++) {
              if (i < cooldownUntil) continue;
              if (sim.calculateHeuristic(pkg, sIdx, i) > sim.params.buyThreshold()) {
                  double buyPrice = pkg.closePrices[sIdx][i];

                  if (ml && i >= pkg.offsets[sIdx] + 30 && i + 30 < pkg.daysCount) {
                      String pointKey = sIdx + ":" + i;
                      if (!collectedPoints.contains(pointKey)) {
                          collectMLSample(sim, pkg, sIdx, i, buyPrice);
                          collectedPoints.add(pointKey);
                      }
                  }

                  // Hold period simulation
                  double highestPrice = buyPrice;
                  for (int j = 1; j < absoluteLimit - i; j++) {
                      int curr = i + j;
                      double price = pkg.closePrices[sIdx][curr];
                      highestPrice = Math.max(highestPrice, price);

                      if (price < (highestPrice * sim.params.sellCutOffPerc()) || (curr == absoluteLimit - 1)) {
                          sim.recordTrade((price - buyPrice) / buyPrice, j);
                          cooldownUntil = curr + sim.params.cooldownDays() + 1;
                          i = curr;
                          break;
                      }
                  }
              }
          }
      }
  ```

- [ ] **Step 2: Update GenerateKey**
  Modify [Simulation.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/core/Simulation.java#L48) to include `cooldownDays`:
  ```java
      public static String GenerateKey(SimulationParams p) {
          return String.format("%s,%s,%s,%d,%s,%d,%d,%s,%s,%d,%s,%d,%s,%s,%s,%s,%s,%d",
                  p.sellCutOffPerc(), p.lowerPriceToLongAvgBuyIn(), p.higherPriceToLongAvgBuyIn(),
                  p.timeFrameForUpwardLongAvg(), p.aboveAvgRatingPricePerc(), p.timeFrameForUpwardShortPrice(),
                  p.timeFrameForOscillator(), p.maxRSI(), p.minMarketCap(), p.longMovingAvgTime(),
                  p.minRateOfAvgInc(), p.maxPERatio(), p.minRating(), p.maxRating(), p.maxMarketCap(),
                  p.riskFreeRate(), p.buyThreshold(), p.cooldownDays());
      }
  ```

- [ ] **Step 3: Run Simulation tests**
  Run: `mvn test -Dtest=CpuParamOptimizerTest`
  Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit changes**
  Run: `git commit -am "feat: implement cooldown enforcement on CPU"`

---

### Task 3: CpuParamOptimizer Modifications

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/service/CpuParamOptimizer.java`

- [ ] **Step 1: Update CpuParamOptimizer.randomize**
  Modify [CpuParamOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/CpuParamOptimizer.java#L257) to randomize and clamp `cooldownDays`:
  ```java
      public SimulationParams randomize(SimulationParams c, double r) {
          double lowerGap = clamp(c.lowerPriceToLongAvgBuyIn() + rand(0.80 * r), 0.40, 1.20);
          ...
          return new SimulationParams(
                  sellCutoff,
                  lowerGap,
                  higherGap,
                  clampInt(c.timeFrameForUpwardLongAvg() + randInt((int) (240 * r)), 10, 250),
                  clamp(c.aboveAvgRatingPricePerc() + rand(0.90 * r), 0.90, 1.80),
                  clampInt(c.timeFrameForUpwardShortPrice() + randInt((int) (19)), 1, 20),
                  clampInt(c.timeFrameForOscillator() + randInt((int) (190 * r)), 1, 200),
                  clamp(c.maxRSI() + rand(55.0 * r), 30.0, 85.0),
                  Math.max(10, c.minMarketCap() * (1 + rand(r))),
                  clampInt(c.longMovingAvgTime() + randInt((int) (240 * r)), 10, 250),
                  clamp(c.minRateOfAvgInc() + rand(0.50 * r), 0.90, 1.40),
                  clampInt(c.maxPERatio() + randInt((int) (145 * r)), 5, 150),
                  minR,
                  maxR,
                  Math.max(1000, c.maxMarketCap() * (1 + rand(r))),
                  c.riskFreeRate(),
                  clamp(c.buyThreshold() + rand(3.40 * r), 0.5, 3.90),
                  clamp(c.movingAvgGapWeight() + rand(r), 0.0, 1.0),
                  clamp(c.reversionToMeanWeight() + rand(r), 0.0, 1.0),
                  clamp(c.ratingWeight() + rand(r), 0.0, 1.0),
                  clamp(c.upwardIncRateWeight() + rand(r), 0.0, 1.0),
                  clamp(c.rvolWeight() + rand(r), 0.0, 1.0),
                  clamp(c.pegWeight() + rand(r), 0.0, 1.0),
                  clamp(c.volatilityCompressionWeight() + rand(r), 0.0, 1.0),
                  clampInt(c.cooldownDays() + randInt((int) (5 * r)), 1, 20)
          );
      }
  ```

- [ ] **Step 2: Update centerParamsFromConfig**
  Modify [CpuParamOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/CpuParamOptimizer.java#L293) to read `cooldownDays`:
  ```java
      private SimulationParams centerParamsFromConfig() {
          return new SimulationParams(
                  config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                  config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                  config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                  config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                  config.riskFreeRate.get(0), config.buyThreshold == null || config.buyThreshold.isEmpty() ? 0.9 : config.buyThreshold.get(0),
                  config.movingAvgGapWeight == null || config.movingAvgGapWeight.isEmpty() ? 0.2 : config.movingAvgGapWeight.get(0),
                  0.3, 0.3, 0.3, 0.3, 0.3, 0.3,
                  config.cooldownDays == null || config.cooldownDays.isEmpty() ? 5 : config.cooldownDays.get(0)
          );
      }
  ```

- [ ] **Step 3: Align trade density threshold in CpuParamOptimizer**
  Modify [CpuParamOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/CpuParamOptimizer.java#L151) to compute normalized `minRequiredTrades`:
  ```java
                  // Total evaluation instances: stocks * grid points
                  long totalEvaluations = (long) stockSubset.size() * frames;

                  // Aligned trade density requirements
                  double minRequiredTrades = Math.max(5, (double) stockSubset.size() * frames / 100.0);
                  boolean hasVolume = trades >= minRequiredTrades;
                  double score = rescue ? (-100.0 + trades) : (hasVolume ? sim.calculateScore(totalEvaluations) : -100.0);
  ```

- [ ] **Step 4: Commit changes**
  Run: `git commit -am "feat: align trade density threshold and randomize cooldownDays in CPU optimizer"`

---

### Task 4: GPU Optimizer Parameter Mapping & Score Alignment

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Increase PARAMETER_STRIDE and add cooldownDays mapping**
  Modify [TornadoVmOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java#L41) to update stride:
  ```java
      private static final int PARAMETER_STRIDE = 25;
  ```
  Update `mapParamsToFloatArray()` to write to index 24:
  ```java
      private void mapParamsToFloatArray(SimulationParams params, FloatArray array, int offset) {
          ...
          array.set(offset + 23, (float) params.volatilityCompressionWeight());
          array.set(offset + 24, (float) params.cooldownDays());
      }
  ```

- [ ] **Step 2: Update centerParamsFromConfig in TornadoVmOptimizer**
  Modify [TornadoVmOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java#L610) to read `cooldownDays`:
  ```java
      private SimulationParams centerParamsFromConfig() {
          return new SimulationParams(
                  config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                  config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                  config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                  config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                  config.riskFreeRate.get(0), config.buyThreshold == null || config.buyThreshold.isEmpty() ? 0.65 : config.buyThreshold.get(0),
                  config.movingAvgGapWeight == null || config.movingAvgGapWeight.isEmpty() ? 0.2 : config.movingAvgGapWeight.get(0),
                  0.15, 0.2, 0.15, 0.1, 0.1, 0.1,
                  config.cooldownDays == null || config.cooldownDays.isEmpty() ? 5 : config.cooldownDays.get(0)
          );
      }
  ```

- [ ] **Step 3: Align calculateCandidateScore and remove GPU tie-breaker**
  Modify [TornadoVmOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java#L258):
  ```java
      private double calculateCandidateScore(double trades, double excess, double sqExcess, double totalHoldingDays,
                                             int subsetSize, int gridCount, boolean rescue) {
          if (rescue) return -100.0 + trades;

          double minRequiredTrades = Math.max(5, (double) subsetSize * gridCount / 100.0);
          if (trades < minRequiredTrades || totalHoldingDays < minRequiredTrades * 2.0) return -100.0;

          double avgDailyExcess = excess / totalHoldingDays;
          double variance = (sqExcess - (excess * excess / totalHoldingDays)) / (totalHoldingDays - 1.000001);
          double stdDev = Math.sqrt(Math.max(0.0, variance));

          double annualizedExcess = avgDailyExcess * 252.0;
          double annualizedStdDev = stdDev * Math.sqrt(252.0);

          double sharpe = (annualizedExcess / (annualizedStdDev + 0.01));

          if (annualizedExcess < 0) sharpe = annualizedExcess * (annualizedStdDev + 1.0);

          return sharpe; // Tie-breaker removed
      }
  ```

- [ ] **Step 4: Commit changes**
  Run: `git commit -am "feat: align threshold and remove tie-breaker from TornadoVmOptimizer"`

---

### Task 5: GPU Kernel Cooldown Enforcement

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java`

- [ ] **Step 1: Update unifiedKernel to enforce cooldownDays**
  Modify [TornadoVmOptimizer.java](file:///C:/Users/NF/IdeaProjects/stock-analyzer-java/backend/src/main/java/com/stock/analyzer/service/TornadoVmOptimizer.java#L291)'s `unifiedKernel()`:
  - Load `cooldownDays` parameter from matrix.
  - Define `cooldownUntilDay` state variable.
  - Exclude trades when inside `cooldownUntilDay`.
  - Update `cooldownUntilDay` on trade sell.
  ```java
      public static void unifiedKernel(FloatArray technicalData, IntArray subsetIndices, IntArray stockOffsets,
                                       FloatArray parameterMatrix, IntArray simulationGrid,
                                       FloatArray optimizationResults, int maxStocks, int totalDays, int maxBatchSize, int gridCount, int totalElements,
                                       int activeSubsetSize, int activeBatchSize) {

          for (@Parallel int globalIdx = 0; globalIdx < totalElements; globalIdx++) {
              ...
              float totalWeight = parameterMatrix.get(paramBase + 17) + parameterMatrix.get(paramBase + 18) +
                      parameterMatrix.get(paramBase + 19) + parameterMatrix.get(paramBase + 20) +
                      parameterMatrix.get(paramBase + 21) + parameterMatrix.get(paramBase + 22) +
                      parameterMatrix.get(paramBase + 23) + 1e-6f;

              float sellCutoff = parameterMatrix.get(paramBase);
              int longAvgTimeframe = (int) parameterMatrix.get(paramBase + 9);
              float dailyRiskFreeRate = parameterMatrix.get(paramBase + 15);
              float buyThreshold = parameterMatrix.get(paramBase + 16);
              int cooldownDays = (int) parameterMatrix.get(paramBase + 24); // Load cooldown

              int stockBaseIndex = globalStockIdx * totalDays;
              ...
              int tradingState = 0;
              float entryPrice = 0.0f, entryDay = 0.0f, highestPrice = 0.0f;
              float trades = 0, holdingDays = 0, sumExcess = 0, sumSqExcess = 0, sumTotalExcess = 0;
              int cooldownUntilDay = 0; // Initialize cooldown until day

              for (int d = simStart; d < finalLimit; d++) {
                  int dayOffset = (stockBaseIndex + d) * TECH_DATA_STRIDE;
                  float price = technicalData.get(dayOffset);

                  // inline shouldSkip (mActive requires d >= minStart)
                  ...
                  float isActive = condCapMin * condCapMax * mShort * condPrice * condMinStart * (float) isThreadActive;
                  int shouldSkip = (int) (1.0f - isActive);

                  float heuristic = 0.0f;
                  ...
                  float fullHeuristic = (scoreGap + scoreRating + scoreRev + scoreVol + scoreRVol + scoreMom) / totalWeight;
                  heuristic = (float) (1 - shouldSkip) * fullHeuristic;

                  int isCooldowned = (d < cooldownUntilDay) ? 1 : 0; // Check cooldown state

                  int condBuy1 = (tradingState == 0) ? 1 : 0;
                  int condBuy2 = (d < buyLimit) ? 1 : 0;
                  int condBuy3 = (heuristic > buyThreshold) ? 1 : 0;
                  int doBuy = condBuy1 * condBuy2 * condBuy3 * isThreadActive * (1 - isCooldowned); // Apply cooldown

                  int condSell1 = (tradingState == 1) ? 1 : 0;
                  int condSell2 = (price < highestPrice * sellCutoff) ? 1 : 0;
                  int condSell3 = (d == finalLimit - 1) ? 1 : 0;
                  int condSell23 = (condSell2 + condSell3 > 0) ? 1 : 0;
                  int doSell = condSell1 * condSell23 * isThreadActive;

                  entryPrice = doBuy * price + (1 - doBuy) * entryPrice;
                  entryDay = doBuy * (float) d + (1 - doBuy) * entryDay;
                  highestPrice = doBuy * price + (1 - doBuy) * Math.max(highestPrice, price);

                  float dur = (float) d - entryDay;
                  float rawRet = (price - entryPrice) / (entryPrice + 1e-6f) - 0.003f; // Align slippage to 0.003f

                  rawRet = Math.max(-1.0f, Math.min(1.0f, rawRet));

                  float tradeLogRet = TornadoMath.log(1.0f + rawRet);
                  float excessLogRet = tradeLogRet - (dur * dailyRiskFreeRate);
                  float safeDur = Math.max(0.1f, dur);
                  float dailyExcess = excessLogRet / safeDur;

                  int condCommit1 = (doSell == 1) ? 1 : 0;
                  int condCommit2 = (dur > 0.1f) ? 1 : 0;
                  int commit = condCommit1 * condCommit2;

                  trades += (float) commit;
                  holdingDays += (float) commit * dur;
                  sumExcess += (float) commit * excessLogRet;
                  sumSqExcess += (float) commit * (dailyExcess * dailyExcess * dur);
                  sumTotalExcess += (float) commit * tradeLogRet;

                  tradingState = doBuy * 1 + doSell * 0 + (1 - doBuy - doSell) * tradingState;
                  cooldownUntilDay = doSell * (d + cooldownDays + 1) + (1 - doSell) * cooldownUntilDay; // Update cooldown
              }

              optimizationResults.set(outputIdx, trades);
              optimizationResults.set(outputIdx + 1, holdingDays);
              optimizationResults.set(outputIdx + 2, sumExcess);
              optimizationResults.set(outputIdx + 3, sumSqExcess);
              optimizationResults.set(outputIdx + 4, sumTotalExcess);
          }
      }
  ```

- [ ] **Step 2: Commit changes**
  Run: `git commit -am "feat: implement cooldown logic inside the OpenCL unifiedKernel"`

---

### Task 6: verification and E2E Tests

**Files:**
- Modify: `backend/src/test/java/com/stock/analyzer/service/TornadoVmOptimizerTest.java`

- [ ] **Step 1: Update parity assertions in testParityWithNegativeReturn**
  Verify that both CPU and GPU match and produce negative Sharpe ratios correctly (without the unnormalized tie-breaker).
  Run: `mvn test -Dtest=TornadoVmOptimizerTest`
  Expected: BUILD SUCCESS (All tests pass, including the new negative-return parity test).

- [ ] **Step 2: Verify all backend tests**
  Run: `mvn test`
  Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit all changes**
  Run: `git commit -am "test: verify complete CPU/GPU parity with negative returns and cooldownDays"`
