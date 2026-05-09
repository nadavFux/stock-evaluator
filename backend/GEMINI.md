# TornadoVM Development Guidelines

This document outlines the architectural mandates and best practices for developing high-performance GPU kernels using TornadoVM in this project. These rules were established after resolving critical performance hangs and JIT overhead issues in the `TornadoVmOptimizer`.

## 1. Core Architecture: The Unified Kernel
**Mandate**: Prefer a single, unified kernel over multiple task-dependent kernels.
*   **Why?**: Managing intermediate buffers (e.g., `heuristicScoreMatrix`) as outputs of one task and inputs to another often leads to device-side allocation failures or synchronization hangs on drivers with limited VRAM.
*   **Practice**: Merge indicator derivation, heuristic scoring, and simulation into a single pass per thread. While it increases the computational load per thread, it significantly reduces global memory traffic and PCI-e overhead.

## 2. Register-Heavy Optimization
**Mandate**: Always load candidate-specific parameters into local variables (registers) before entering a high-frequency loop.
*   **Why?**: Reading from `FloatArray` (global memory) inside a tight loop (e.g., a daily price loop) is extremely slow and can trigger GPU TDR (Timeout Detection and Recovery).
*   **Practice**:
    ```java
    // DO THIS: Load once outside the loop
    float buyThreshold = parameterMatrix.get(paramBase + 16);
    for (int d = start; d < end; d++) {
        if (heuristic > buyThreshold) { ... }
    }
    ```

## 3. JIT & Plan Management
**Mandate**: Plan IDs should vary only by invariant data dimensions, not by dynamic batch counts.
*   **Why?**: Overly specific Plan IDs trigger redundant JIT compilations, making the system appear "stuck" for minutes while the GPU driver generates binaries.
*   **Practice**: Use plan IDs like `unified_s[subsetSize]_g[gridCount]`. Do NOT include the current batch size in the ID if the kernel logic can handle it via a parameter.

## 4. Mathematical Stability
**Mandate**: Use explicit clamping and prefer simple arithmetic over complex math in inner loops.
*   **Why?**: GPU kernels are sensitive to NaN/Infinity propagation. `TornadoMath.pow` can be unstable or slow in high-frequency tight loops.
*   **Practice**:
    *   Use ternary operators for manual clamping: `val = (val < 0 ? 0 : (val > 1 ? 1 : val))`.
    *   Use simple interest approximations for the risk-free rate on the GPU where appropriate.
    *   Always add a guard for price validity: `if (price <= 0) continue;`.

## 5. Buffer Stride Consistency
**Mandate**: Use named constants for all buffer strides (e.g., `TECH_DATA_STRIDE = 12`) and ensure they are synchronized across hydration logic and kernel access.
*   **Why?**: Hardcoded magic numbers lead to "off-by-one" memory corruption that is nearly impossible to debug on the GPU.

## 6. Data Transfer Modes
**Mandate**: Use `FIRST_EXECUTION` for massive static data (prices, offsets) and `EVERY_EXECUTION` for small dynamic data (parameters, results).
*   **Why?**: Minimizes PCI-e bandwidth bottlenecks while ensuring the GPU always has the latest parameters for the current optimization batch.
