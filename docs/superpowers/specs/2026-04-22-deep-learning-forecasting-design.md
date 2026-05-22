# Design Spec: Deep Learning Forecasting with DJL

## 1. Overview
This module upgrades the existing Random Forest-based stock analysis to a temporal Deep Learning model using Long Short-Term Memory (LSTM) networks. The goal is to predict the stock price 30 trading days into the future, providing not just a point estimate but also a 90% confidence interval (Quantile Regression).

## 2. Architecture
- **Engine:** Deep Java Library (DJL) with PyTorch engine.
- **Model Type:** Quantile Regression LSTM.
- **Input Shape:** `(Batch, 30, 12)` - 30 days of 12 features each.
- **Layers:**
    1. **LSTM Layer 1:** 64 units, returns sequences.
    2. **LSTM Layer 2:** 32 units, returns final state.
    3. **Fully Connected Layer:** 3 outputs representing the 0.05, 0.50 (median), and 0.95 quantiles.
- **Loss Function:** Custom **Pinball Loss** (Mean Quantile Loss) to optimize for specific quantiles.

## 3. Data Pipeline & Features
### 3.1 Feature Vector (12 Dimensions)
For each day in the 30-day sequence:
1. **MA Gap:** Price / 200-day Moving Average.
2. **MA Dist:** Absolute distance from the 200-day MA.
3. **Rating:** Analyst consensus score (1.0 - 5.0).
4. **Momentum:** Slope of the 50-day Moving Average.
5. **RVOL:** Relative Volume (Current / 30-day Average).
6. **PEG Ratio:** Price-to-Earnings / Growth Rate.
7. **RSI (14):** Relative Strength Index.
8. **ATR %:** Average True Range as a percentage of price.
9. **MACD Histogram:** Difference between MACD line and Signal line.
10. **Bollinger %B:** Position of price relative to Bollinger Bands.
11. **Volatility:** 20-day standard deviation of returns.
12. **Sector RS:** Relative Strength of the stock's sector vs. the benchmark index.

### 3.2 Preprocessing
- **Normalization:** Z-score scaling (StandardScaler) per feature across the training set.
- **Sequence Generation:** Sliding window of 30 trading days.
- **Labeling:** Target price is the price exactly 30 trading days after the window ends.

## 4. Training & Validation
- **Horizon:** 30 Trading Days.
- **Validation:** Walk-Forward Validation (training on historical blocks, validating on the subsequent year).
- **Early Stopping:** Stops training when validation Pinball Loss plateaus.
- **Persistence:** Models saved as `.pt` (PyTorch) files within the `output/models` directory.

## 5. UI Integration
- **Price Target:** Displayed as a numeric value in the "Signal Rationale" section.
- **Confidence Band:** Visualized as a horizontal range bar showing the 0.05 to 0.95 quantile range.
- **Risk/Reward:** Calculated based on current price vs. the predicted median and lower-bound "stop-loss" quantile.

## 6. Testing Strategy
- **Unit Tests:** Verify `StatsCalculator` correctly computes the 5 new indicators (RSI, ATR, MACD, BB, Sector RS).
- **Integration Tests:** Ensure the `MLModelService` can successfully load a `.pt` model and return a 3-unit quantile array.
- **Backtest Verification:** Compare the "Predicted vs. Actual" price for historical 30-day windows.
