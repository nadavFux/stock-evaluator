# Stock Analyzer Java - Project Overview

A high-performance stock analysis and investment entry-point optimization suite.

## Tech Stack

### Backend
- **Framework:** Spring Boot 3.1.5 (Java 17)
- **Data Processing:** Native array-based optimization (`SimulationDataPackage`) for O(1) technical indicator retrieval.
- **Machine Learning:** Deep Java Library (DJL) with LSTM-based Quantile Regression (Q5, Q50, Q95) for return prediction and uncertainty estimation.
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
   - **Hydration:** Fetching and preparing stock data into `SimulationDataPackage` with pre-computed prefix sums and indicators.
   - **Optimization:** `ParamOptimizer` runs parallel simulations to find the best trading parameters using iterative random search and zoom optimization.
   - **ML Training:** `MLModelService` extracts sequence features and trains a Quantile LSTM model using Pinball Loss.
   - **Inference:** Applies best parameters and ML model to current market data.
   - **Broadcasting:** Sends `STATUS`, `PROGRESS`, `ML_FEATURES`, and `RESULTS` to the frontend via WebSockets.

3. **Simulation Engine:**
   - `Simulation` class handles heuristic scoring and AI predictions with statistical pruning for performance.
   - Traditional indicators (MA Gap, RSI, Momentum, RVol, PEG, Volatility) are blended with AI-predicted returns for a final recommendation.

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
