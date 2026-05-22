# Stock Analyzer Java - Project Overview

A high-performance stock analysis and investment entry-point optimization suite.

## Tech Stack

### Backend
- **Framework:** Spring Boot 3.4.0 (Java 21 LTS)
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

The GPU optimizer leverages a **Unified Kernel** architecture designed for maximum throughput, JIT stability, and bit-level parity with the CPU simulation.

### 1. The Unified Kernel Design
Unlike multi-stage pipelines that suffer from VRAM fragmentation and PCIe overhead, our **Unified Kernel** (`unifiedKernel`) performs all calculations in a single device-side pass per thread:
- **Indicator Derivation:** Calculates MA Gaps, RVol, Rating, Volatility, and Momentum on-the-fly.
- **Heuristic Scoring:** Computes normalized entry scores using branchless logic.
- **Simulation Execution:** Runs the trading simulation directly within the same kernel, eliminating intermediate buffer transfers.

### 2. Performance Critical Insights
- **Zero-Copy Optimization:** By merging all logic into one kernel, intermediate results stay in registers rather than VRAM. We only transfer the final results buffer back to the host, eliminating substantial bandwidth bottlenecks.
- **Register-Heavy Engineering:** Candidate parameters are loaded into local variables (registers) before entering high-frequency loops to minimize global memory access and prevent TDR timeouts.
- **JIT-Safe Engineering:** The kernel is implemented using **flat control flow**, **integer flags** instead of `while/break`, and **branchless ternary operations**. This ensures high occupancy and prevents driver-level JIT bailouts.
- **Mathematical Parity:** The GPU implementation maintains bit-level parity with `Simulation.java` through synchronized Indicator calculation logic and shared random seed validation.
- **Buffer Reuse & Memory Fragmentation:** Frequent re-allocation of large `DoubleArray` or `IntArray` buffers triggers `CL_OUT_OF_RESOURCES` (-5) errors. Always reuse instance-level GPU buffers across executions.
- **Resource Constraints (2GB VRAM):** For consumer GPUs with 2GB VRAM (e.g., GTX 750 Ti), the maximum candidate batch size MUST be restricted to **20** to prevent TDR timeouts and memory exhaustion. Additionally, all Deep Learning (DJL/PyTorch) tasks MUST be forced to the **CPU** to preserve VRAM for technical indicator parallelization.
- **Data Transfer Optimization:** Use `DataTransferMode.EVERY_EXECUTION` for buffers that change per iteration (e.g., parameter matrices, stock subsets) and `FIRST_EXECUTION` for massive static data (prices, offsets) to minimize PCIe overhead.

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
