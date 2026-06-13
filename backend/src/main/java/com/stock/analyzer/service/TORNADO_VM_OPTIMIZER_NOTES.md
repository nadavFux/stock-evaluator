# TornadoVM GPU Kernel Optimization Notes

This file summarizes key lessons and engineering guidelines learned while refactoring and optimizing the `TornadoVmOptimizer` (GPU acceleration suite) using TornadoVM 4.0.0 (OpenCL).

---

## 1. GraalVM SSA Compiler Hangs
* **The Symptom**: During JIT compilation of `unifiedKernel`, the Graal compiler froze in `SSAVerifier.doBlock`.
* **The Cause**: Graal JIT compiler tries to construct and verify the Static Single Assignment (SSA) form of the Low-Level Intermediate Representation (LIR). Deeply nested loops with helper method calls (`getMovingAverage`, `calcBaseScore`, etc.) led to highly complex Control Flow Graphs (CFGs) that Graal's verifiers struggled to analyze in a reasonable time.
* **The Solution**: 
  1. **Disable Assertions**: Graal verifier assertions must be disabled globally during testing by passing `-da` to the JVM and setting `<enableAssertions>false</enableAssertions>` in the Maven Surefire configuration.
  2. **Flatten Control Flow**: Inline helper methods and refactor control flow to make the CFG as simple and flat as possible.

---

## 2. OpenCL Build Errors (`extraneous closing brace ('}')`)
* **The Symptom**: The JIT compiler generated invalid OpenCL code, throwing `<kernel>: error: extraneous closing brace ('}')` and triggering `[Bailout] Running the sequential implementation`.
* **The Cause**: Inlining branchy statements (such as `if (!shouldSkip)`) and logical `&&` or `||` operators inside deeply nested loops caused Graal's C-code generator to output mismatched braces or incorrect scoping brackets.
* **The Solution**: Eliminate all branches from the hot path loop. All conditional logic must be evaluated branchlessly.

---

## 3. Guidelines for 100% Branchless Hot-Paths
To achieve maximum warp uniformity, avoid driver compilation failures, and prevent JIT bailouts, adhere to these guidelines:

* **Use Intrinsic Math Primitives**:
  * Instead of `(a > b) ? a : b`, use `Math.max(a, b)`.
  * Instead of `(a < b) ? a : b`, use `Math.min(a, b)`.
  * Instead of variable clamping loops, use `Math.max(low, Math.min(high, value))`.
  * *Why?* TornadoVM maps `Math.max` and `Math.min` directly to built-in OpenCL C functions (`max` and `min`) which compile to single-cycle hardware instructions without generating branches.
* **Replace Boolean Branching with Arithmetic Multiplexing**:
  * Represent boolean conditions as integer or float flags (`0.0f` or `1.0f`).
  * Replace `if (condition) { val = A; } else { val = B; }` with:
    ```java
    float condVal = (condition) ? 1.0f : 0.0f;
    float val = condVal * A + (1.0f - condVal) * B;
    ```
* **Convert Logical Chains to Bitwise/Arithmetic Operators**:
  * Replace logical `&&` with multiplication:
    ```java
    // Instead of: (cond1 && cond2 && cond3)
    int combined = cond1 * cond2 * cond3; // 1 if all are 1, else 0
    ```
  * Replace logical `||` with clamp:
    ```java
    // Instead of: (cond1 || cond2)
    int combined = Math.min(1, cond1 + cond2);
    ```
* **Avoid Implicit Double-Precision Floating-Point Operations**:
  * Standard `java.lang.Math.log` only has a `double` overload. Passing a `float` implicitly promotes the argument and returns a `double`, forcing TornadoVM's JIT to compile 64-bit double operations. On consumer GPUs without native 64-bit float support (missing `cl_khr_fp64` OpenCL extension), the OpenCL driver fails with compilation errors (`clBuildProgram Returned: -11` and `expected '}'`).
  * Replace `Math.log` with `TornadoMath.log`, which works natively with `float` arguments and returns `float`, translating cleanly to standard single-precision OpenCL C math built-ins.

---

## 4. VRAM Footprint & Bandwidth Optimization
* **The Symptom**: Consumer GPUs with limited VRAM (e.g. 2GB) crashed with `CL_OUT_OF_RESOURCES` (-5) or suffered PCIe bottlenecks when moving huge static/result buffers.
* **The Optimization**:
  1. **Device-Side Loop Accumulation**: Instead of passing every individual grid result back to the host, loop over the parameter grid internally on the GPU. Accumulate metrics in thread-local registers (e.g. `trades`, `holdingDays`), and write only the final aggregated value once to the output buffer at the end of the execution.
  2. **Footprint Reduction**: Removing the `gridCount` multiplier from the result buffer stride reduces memory consumption by a factor of 100x+, bringing the footprint down from gigabytes to megabytes, saving substantial PCIe transfer bandwidth.
