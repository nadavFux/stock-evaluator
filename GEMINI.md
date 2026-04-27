# Stock Analyzer Java - Project Overview

A high-performance stock analysis and investment entry-point optimization suite.

## Tech Stack

### Backend
- **Framework:** Spring Boot 3.1.5 (Java 21 LTS)
- **Acceleration:** **TornadoVM 4.0.0 (OpenCL)** for massive parallel GPU parameter optimization.
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

## GPU Architecture (`TornadoVmOptimizer`)

The GPU optimizer leverages a **3-Stage Pipeline** architecture designed for maximum throughput and JIT stability.

### 1. The Pipeline Stages
- **Stage 1: Indicator Kernel:** Pre-calculates MA Gaps, RVol, Rating, Volatility, and Momentum for every stock/day combination.
- **Stage 2: Heuristic Scorer Kernel:** Computes normalized entry scores (0.0-1.0) using weights for all Centers × Stocks × Days. Uses branchless logic to bypass OpenCL JIT complexities.
- **Stage 3: Simulation Kernel:** Executes parallel trading simulations across thousands of parameter/stock pairs. Handles complex entry/exit logic entirely on the GPU to prevent CPU starvation.

### 2. Performance Critical Insights
- **Zero-Copy Memory Handover:** Intermediate results (like the ~200MB Heuristic Matrix) stay on the GPU VRAM between stages. We only transfer the final ~40KB results buffer back to the host, eliminating a 20GB/gen bandwidth bottleneck.
- **JIT-Safe Engineering:** OpenCL compilers are sensitive to deep nesting. All kernels must use **flat control flow**, **integer flags** instead of `while/break`, and **branchless ternary operations** to ensure high utilization and prevent bailouts to the CPU.
- **Mathematical Parity:** The GPU implementation maintains bit-level parity with `Simulation.java` through synchronized Indicator calculation logic and shared random seed validation.
- **Kernel Splitting for JIT Stability:** Complex kernels (e.g., combining indicator logic and heuristic scoring) can cause TornadoVM's JIT to generate malformed OpenCL or hang during the "Execution Graph" phase. Splitting into a **3-Stage Pipeline** (`indicator` -> `heuristic` -> `simulation`) is mandatory for stability on consumer-grade GPU drivers.
- **Buffer Reuse & Memory Fragmentation:** Frequent re-allocation of large `DoubleArray` or `IntArray` buffers (e.g., `sMat`, `hScores`) inside high-frequency optimization loops triggers `CL_OUT_OF_RESOURCES` (-5) errors due to VRAM fragmentation. Always promote large GPU buffers to instance fields and reuse them across executions, resizing only when necessary.
- **Data Transfer Optimization:** Use `DataTransferMode.EVERY_EXECUTION` for buffers that change per iteration (e.g., parameter matrices, stock subsets) and `FIRST_EXECUTION` for intermediate device-side results to minimize PCIe overhead.

### 3. Runtime Requirements
To enable GPU acceleration, the JVM must be launched with:
- `@C:\Users\NF\tornadovm-4.0.0-opencl\tornado-argfile` (Loads the native OpenCL runtime).
- `--enable-preview --enable-native-access=ALL-UNNAMED` (Mandatory for Java 21 + TornadoVM).

## Core Architecture & Flows

1. **Data Acquisition:**
   - `Pipeline` & `StockDataService` fetch data across sectors and exchanges (NYSE, NASDAQ, TASE).
   - `GraphingService` retrieves historical price data for charting and simulation.

2. **Analysis Pipeline (`AnalysisService`):**
   - **Hydration:** Fetching and preparing stock data into `SimulationDataPackage` with pre-computed prefix sums and indicators.
   - **Optimization:** `OptimizerFactory` chooses between `CpuParamOptimizer` or `TornadoVmOptimizer` (GPU). Parallel simulations find the best parameters using iterative random search.
   - **ML Training:** `MLModelService` extracts sequence features and trains a Quantile LSTM model using Pinball Loss.
   - **Inference:** Applies best parameters and ML model to current market data.
   - **Broadcasting:** Sends `STATUS`, `PROGRESS`, `ML_FEATURES`, and `RESULTS` to the frontend via WebSockets.

3. **Simulation Engine:**
   - `Simulation` class handles heuristic scoring and AI predictions with statistical pruning for performance.
   - Traditional indicators (MA Gap, RSI, Momentum, RVol, PEG, Volatility) are blended with AI-predicted returns for a final recommendation.

## Massive Upgrade Roadmap (Current Progress)

### 1. Analytics & ML ✅ (GPU Optimized)
- **TornadoVM Integration:** Successfully parallelized parameter discovery across 50,000+ threads.
- **Deep Learning:** DJL Quantile LSTM implemented for uncertainty estimation.

### 2. Professional Frontend
- **Advanced UI:**
  - Full-screen interactive charts with drawing tools.
  - Comparison mode for multiple tickers.
  - Heatmaps for sector performance.

### 3. Enterprise Features
- **Portfolio Management:** Track portfolios and get tailored recommendations.
- **Backtesting Lab:** UI for running manual backtests with custom strategies.

## Session Guidelines
- Always prioritize type safety in both Java and TypeScript.
- **GPU Maintenance:** When modifying indicators in `StatsCalculator`, always update the corresponding logic in `TornadoVmOptimizer.indicatorKernel` and `heuristicKernel` to maintain logic parity.
- **Validation:** Use `TornadoVmOptimizerTest.testGpuCpuParity()` to verify that code changes haven't introduced divergence between CPU and GPU results.
- Follow the **Research -> Strategy -> Execution** lifecycle for all upgrades.
- Act as an experienced senior developer and domain expert in finance.
