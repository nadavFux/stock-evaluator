# Deep Learning Forecasting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a 30-day price target forecasting model using Quantile LSTM via Deep Java Library (DJL) and PyTorch.

**Architecture:** Use DJL's Sequential Block API to build a two-layer LSTM with a Dense output layer for 0.05, 0.50, and 0.95 quantiles. Preprocess market data into normalized 30-day sequences.

**Tech Stack:** Java 17, Spring Boot, DJL (PyTorch), React (TypeScript).

---

### Task 1: Add DJL Dependencies

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Add DJL and PyTorch dependencies**
Update the `<dependencies>` section in `backend/pom.xml`.

```xml
        <!-- DJL Dependencies -->
        <dependency>
            <groupId>ai.djl</groupId>
            <artifactId>api</artifactId>
            <version>0.26.0</version>
        </dependency>
        <dependency>
            <groupId>ai.djl.pytorch</groupId>
            <artifactId>pytorch-engine</artifactId>
            <version>0.26.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>ai.djl.pytorch</groupId>
            <artifactId>pytorch-native-auto</artifactId>
            <version>2.1.1</version>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Verify dependencies load**
Run: `mvn dependency:resolve -f backend/pom.xml`
Expected: SUCCESS

- [ ] **Step 3: Commit**
```bash
git add backend/pom.xml
git commit -m "infra: add DJL and PyTorch dependencies"
```

---

### Task 2: Implement New Technical Indicators

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/core/StatsCalculator.java`
- Test: `backend/src/test/java/com/stock/analyzer/core/StatsCalculatorTest.java`

- [ ] **Step 1: Implement RSI, ATR, MACD, and Bollinger Bands**
Add the following methods to `StatsCalculator.java`.

```java
    public static double calculateRSI(List<Double> prices, int index, int period) {
        if (index < period) return 50.0;
        double gain = 0, loss = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff >= 0) gain += diff; else loss -= diff;
        }
        double rs = (gain / period) / ((loss / period) + 0.00001);
        return 100 - (100 / (1 + rs));
    }

    public static double calculateATR(List<Double> high, List<Double> low, List<Double> close, int index, int period) {
        if (index < period) return 0.0;
        double trSum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double tr = Math.max(high.get(i) - low.get(i), 
                        Math.max(Math.abs(high.get(i) - close.get(i-1)), Math.abs(low.get(i) - close.get(i-1))));
            trSum += tr;
        }
        return trSum / period;
    }

    public static double calculateMACD(List<Double> prices, int index) {
        double ema12 = calculateEMA(prices, index, 12);
        double ema26 = calculateEMA(prices, index, 26);
        return ema12 - ema26;
    }

    private static double calculateEMA(List<Double> prices, int index, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = prices.get(Math.max(0, index - period));
        for (int i = Math.max(0, index - period) + 1; i <= index; i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }
```

- [ ] **Step 2: Add Bollinger Bands %B and Sector RS**
```java
    public static double calculateBollingerB(List<Double> prices, int index, int period) {
        double ma = calculateSlidingAvg(prices, index, period, "temp");
        double stdDev = calculateVolatility(prices, index, period, "temp");
        if (stdDev == 0) return 0.5;
        return (prices.get(index) - (ma - 2 * stdDev)) / (4 * stdDev);
    }
```

- [ ] **Step 3: Write tests for indicators**
Create `backend/src/test/java/com/stock/analyzer/core/StatsCalculatorTest.java` and verify calculations.

- [ ] **Step 4: Commit**
```bash
git add backend/src/main/java/com/stock/analyzer/core/StatsCalculator.java
git commit -m "feat: add advanced technical indicators to StatsCalculator"
```

---

### Task 3: Update SimulationResult Model

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/model/SimulationResult.java`

- [ ] **Step 1: Add quantile fields to SimulationResult**
Add `q05`, `q50`, `q95` fields and update the constructor.

```java
public record SimulationResult(
    double heuristicScore,
    double aiPredictedReturn,
    double rvol,
    double volatility,
    double momentum,
    double[] features,
    double q05,
    double q50,
    double q95
) {}
```

- [ ] **Step 2: Commit**
```bash
git add backend/src/main/java/com/stock/analyzer/model/SimulationResult.java
git commit -m "model: add quantiles to SimulationResult"
```

---

### Task 4: Implement LSTM Model in MLModelService

**Files:**
- Modify: `backend/src/main/java/com/stock/analyzer/service/MLModelService.java`

- [ ] **Step 1: Define the LSTM Block using DJL**
Implement a method to create the model architecture.

```java
    private Block buildLstmBlock() {
        return new SequentialBlock()
            .add(new LSTM.Builder().setNumLayers(1).setStateSize(64).build())
            .add(new LSTM.Builder().setNumLayers(1).setStateSize(32).build())
            .add(Linear.builder().setUnits(3).build()); // Q5, Q50, Q95
    }
```

- [ ] **Step 2: Implement Pinball Loss**
Since DJL doesn't have Pinball Loss out-of-the-box in Java, we will implement it as a custom function for the training loop.

- [ ] **Step 3: Implement Sequence Preprocessing**
Update `train()` and `predict()` to handle `(30, 12)` input shapes.

- [ ] **Step 4: Commit**
```bash
git add backend/src/main/java/com/stock/analyzer/service/MLModelService.java
git commit -m "feat: implement Quantile LSTM in MLModelService using DJL"
```

---

### Task 5: Update Frontend Signal View

**Files:**
- Modify: `frontend/src/components/StockDashboard.tsx`

- [ ] **Step 1: Update UI to show 30-day forecast and bands**
Update the `Details Modal` section to include the new visual forecast.

```tsx
<div className="bg-[#0f172a] p-6 rounded-2xl border border-slate-800">
    <p className="text-slate-500 text-xs font-bold uppercase mb-2 tracking-widest">30-Day Forecast</p>
    <p className="text-3xl font-bold text-emerald-400">
        ${selectedStock.result.q50.toFixed(2)}
    </p>
    <div className="mt-2 text-[10px] text-slate-500 font-mono">
        Range: ${selectedStock.result.q05.toFixed(2)} - ${selectedStock.result.q95.toFixed(2)}
    </div>
</div>
```

- [ ] **Step 2: Commit**
```bash
git add frontend/src/components/StockDashboard.tsx
git commit -m "ui: display 30-day forecast and confidence bands"
```
