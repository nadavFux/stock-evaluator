# Stock Analyzer Java - Project Overview

A high-performance stock analysis and investment entry-point optimization suite.

## Tech Stack

### Backend
- **Framework:** Spring Boot 3.1.5 (Java 17)
- **Data Processing:** Apache Flink 1.17.0, Apache Spark 3.4.1
- **Machine Learning:** Smile 3.0.2 (Random Forest for return prediction)
- **Communication:** WebSockets (STOMP/SockJS) for real-time updates
- **Database:** H2 (Runtime/Dev), Spring Data JPA
- **APIs:** Integration with Bridgewise, TASE, and Koyfin

### Frontend
- **Framework:** React 19 (TypeScript)
- **Build Tool:** Vite 8.0.4
- **Styling:** Tailwind CSS 4
- **Charts:** Recharts, Lightweight-Charts
- **Icons:** Lucide-React
- **Real-time:** StompJS for WebSocket connectivity

## Core Architecture & Flows

1. **Data Acquisition:**
   - `Pipeline` & `StockDataService` fetch data across sectors and exchanges (NYSE, NASDAQ, TASE).
   - `GraphingService` retrieves historical price data for charting and simulation.

2. **Analysis Pipeline (`AnalysisService`):**
   - **Hydration:** Fetching and preparing stock data.
   - **Optimization:** `ParamOptimizer` runs parallel simulations to find the best trading parameters.
   - **ML Training:** `MLModelService` extracts features (MA Gap, Momentum, RVOL, PEG, Volatility) and trains a Random Forest model.
   - **Inference:** Applies best parameters and ML model to current market data.
   - **Broadcasting:** Sends `STATUS`, `PROGRESS`, `ML_FEATURES`, and `RESULTS` to the frontend via WebSockets.

3. **Simulation Engine:**
   - `Simulation` class handles heuristic scoring and AI predictions.
   - Traditional indicators are blended with AI-predicted returns for a final recommendation.

## Proposed "Massive Upgrade" Roadmap

### 1. Advanced Analytics & ML
- **Deep Learning:** Explore Deep Java Library (DJL) for LSTM/Transformer-based time-series forecasting.
- **Technical Indicators:** Expand `StatsCalculator` with MACD, Bollinger Bands, Ichimoku Clouds, etc.

### 2. Professional Frontend
- **Advanced UI:**
  - Full-screen interactive charts with drawing tools.
  - Comparison mode for multiple tickers.
  - Heatmaps for sector performance.
  - More

### 3. Enterprise Features
- **Portfolio Management:** Allow users to track their own portfolios and get tailored recommendations.
- **Backtesting Lab:** A dedicated UI section for running manual backtests with custom strategies.

## Session Guidelines
- Always prioritize type safety in both Java and TypeScript.
- Maintain the high-signal, real-time feedback loop in the UI.
- Ensure any new indicators are implemented in `StatsCalculator` and integrated into the `Simulation` scoring logic.
- Follow the **Research -> Strategy -> Execution** lifecycle for all upgrades.
- Act as an experienced senior developer when modifying the project
- Act in accordence with proper domain knowledge in investments and stocks 
