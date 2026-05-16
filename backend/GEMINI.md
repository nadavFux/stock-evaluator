# Stock Analyzer - Backend Documentation

This document provides a comprehensive overview of the backend module, its architecture, tech stack, and development mandates.

## 1. Tech Stack
- **Framework:** Spring Boot 3.4.0 (Java 21 LTS)
- **Real-time Communication:** Spring WebSocket (STOMP/SockJS)
- **Database:** H2 Database (In-memory/Runtime persistence)
- **GPU Acceleration:** TornadoVM 4.0.0 (OpenCL)
- **Machine Learning:** Deep Java Library (DJL) with PyTorch engine (CPU-only)
- **Data Serialization:** Jackson (JSON/YAML), GSON
- **Testing:** JUnit 5, SLF4J/Logback

## 2. Core Architecture & Package Structure
The backend follows a service-oriented architecture, organized into the following packages under `com.stock.analyzer`:

- **`api`**: REST Controllers for stock data, sector performance, user profiles, and analysis control.
- **`core`**: The heart of the simulation and analysis logic (`Simulation`, `Pipeline`, `StatsCalculator`).
- **`service`**: Business logic implementations, including data acquisition (`StockDataService`), ML management (`MLModelService`), and parameter optimization (`TornadoVmOptimizer`, `CpuParamOptimizer`).
- **`model`**: Data structures, DTOs, and JPA entities representing stocks, prices, simulation results, and ML features.
- **`infra`**: Infrastructure configuration (Security, WebSockets, Persistence, Global Config).

## 3. Core Development Flows

### A. Data Acquisition & Hydration
1.  **Fetching:** `StockDataService` pulls historical and real-time data from various providers (Bridgewise, TASE, Koyfin).
2.  **Hydration:** Data is transformed into `SimulationDataPackage`, an optimized array-based structure designed for high-speed retrieval and GPU compatibility.

### B. Analysis & Optimization Pipeline
1.  **Initiation:** Triggered via `AnalysisController` or `Pipeline`.
2.  **Optimization:** `OptimizerFactory` selects between `CpuParamOptimizer` or `TornadoVmOptimizer` (GPU).
3.  **GPU Execution:** `TornadoVmOptimizer` executes the `unifiedKernel` across thousands of parameter candidates.
4.  **ML Integration:** `MLModelService` trains an LSTM model on the stock's price history and extracts predictive features.
5.  **Final Recommendation:** Combines best parameters from optimization with ML predictions to generate actionable insights.

### C. Real-time Feedback
All stages of the analysis (Status updates, Progress percentages, and Final Results) are broadcast to the frontend via WebSocket topics (e.g., `/topic/analysis-status`, `/topic/analysis-results`).

## 4. TornadoVM Development Guidelines

These architectural mandates are critical for maintaining the stability and performance of the GPU-accelerated optimizer.

### A. Core Architecture: The Unified Kernel
**Mandate**: Prefer a single, unified kernel over multiple task-dependent kernels.
*   **Why?**: Managing intermediate buffers as outputs of one task and inputs to another often leads to device-side allocation failures or synchronization hangs on drivers with limited VRAM.
*   **Practice**: Merge indicator derivation, heuristic scoring, and simulation into a single pass per thread (`unifiedKernel`).

### B. Register-Heavy Optimization
**Mandate**: Always load candidate-specific parameters into local variables (registers) before entering a high-frequency loop.
*   **Why?**: Reading from `FloatArray` (global memory) inside a tight loop is extremely slow and can trigger GPU TDR (Timeout Detection and Recovery).

### C. JIT & Plan Management
**Mandate**: Plan IDs should vary only by invariant data dimensions, not by dynamic batch counts.
*   **Why?**: Overly specific Plan IDs trigger redundant JIT compilations, causing significant startup delays.

### D. Mathematical Stability
**Mandate**: Use explicit clamping and prefer simple arithmetic over complex math in inner loops.
*   **Why?**: GPU kernels are sensitive to NaN/Infinity propagation. Use ternary operators for manual clamping and guards for price validity.

### E. Buffer Stride Consistency
**Mandate**: Use named constants for all buffer strides (e.g., `PARAMETER_STRIDE`, `TECH_DATA_STRIDE`) and ensure they are synchronized across host-side hydration and device-side kernel access.

### F. Data Transfer Modes
**Mandate**: Use `FIRST_EXECUTION` for massive static data (prices, offsets) and `EVERY_EXECUTION` for small dynamic data (parameters, results).

## 5. Development Mandates
- **Logic Parity:** Any change to indicator logic in `StatsCalculator` MUST be mirrored in `TornadoVmOptimizer.unifiedKernel`.
- **Validation:** Always run `TornadoVmOptimizerTest.testGpuCpuParity()` after modifying kernel logic.
- **VRAM Awareness:** Maintain the 2GB VRAM constraint by capping population batches and forcing ML tasks to the CPU.
- **Type Safety:** Ensure strict adherence to Java 21 features and Spring Boot 3.4 coding standards.
