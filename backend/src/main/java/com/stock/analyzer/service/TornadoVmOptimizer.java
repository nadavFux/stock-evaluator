package com.stock.analyzer.service;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.model.StockGraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-Performance GPU Optimizer using TornadoVM 4.0.0.
 * <p>
 * This optimizer uses a stateless, indexed architecture to process stock data in parallel on the GPU.
 * It implements a "Unified Kernel" approach where technical indicator derivation, heuristic scoring,
 * and trade simulation are performed in a single pass per thread to minimize global memory traffic
 * and ensure maximum execution stability on hardware with limited VRAM (e.g., 2GB).
 */
public class TornadoVmOptimizer implements Optimizer {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizer.class);
    private static final java.util.concurrent.atomic.AtomicInteger taskCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private static boolean isAvailable = false;

    private static FloatArray parameterMatrix;
    private static FloatArray optimizationResults;
    private static IntArray stockOffsets;
    private static IntArray subsetIndices;
    private static IntArray simulationGrid;
    private static IntArray currentGridTask; // <-- ADD THIS
    // Buffer Strides and Layout Constants
    private static final int TECH_DATA_STRIDE = 12;
    private static final int PARAMETER_STRIDE = 24;
    private static final int OPTIMIZATION_RESULT_STRIDE = 5;
    private static final int GRID_TASK_STRIDE = 3;

    private static final int MAX_BATCH_SIZE = 20;

    // Simulation Constants
    private static final int DAYS_PER_YEAR = 252;
    private static final double EPSILON = 0.000001;

    static {
        try {
            var runtime = uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.getTornadoRuntime();
            if (runtime != null && runtime.getNumBackends() > 0) {
                isAvailable = true;
            }
        } catch (Throwable t) {
            logger.error("TornadoVM initialization failed", t);
        }
    }

    public static boolean isAvailable() {
        return isAvailable;
    }

    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final CpuParamOptimizer fallback;

    // Reusable GPU buffers to minimize allocation overhead
    private static FloatArray technicalData;

    private static final Map<String, TornadoExecutionPlan> planCache = new ConcurrentHashMap<>();

    public TornadoVmOptimizer(SimulationRangeConfig config) {
        this.config = config;
        this.fallback = new CpuParamOptimizer(config, this.mlService);
    }

    @Override
    public MLModelService getMlService() {
        return mlService;
    }

    /**
     * Executes a multi-start random search optimization on the GPU.
     */
    @Override
    public SimulationParams optimize(List<StockGraphState> allStocks) {
        synchronized (TornadoVmOptimizer.class) {
            logger.info("Starting GPU Multi-Start Optimization (Refined Architecture)...");
            planCache.clear();
            SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

            preallocateBuffers(dataPkg.stockCount, dataPkg.daysCount);
            flattenToTechData(dataPkg);

            // Initialize multiple starting points (centers) for the search
            int centersCount = (config.centersCount != null) ? config.centersCount : 5;
            int populationSize = (config.populationSize != null) ? config.populationSize : MAX_BATCH_SIZE * 15;
            int totalGenerations = (config.generations != null) ? config.generations : 12;


            List<CandidateResult> centers = new ArrayList<>();
            SimulationParams configCenter = centerParamsFromConfig();
            for (int i = 0; i < centersCount; i++) {
                centers.add(new CandidateResult(i == 0 ? configCenter : fallback.randomize(configCenter, 0.7), -100.0, 0.0));
            }

            for (int i = 0; i < centersCount; i++) {
                logger.info("Evaluating Center {}/{}...", i + 1, centersCount);
                centers.set(i, evaluateCandidate(centers.get(i).params(), dataPkg));
                logger.info("Center {} Score: {}", i, centers.get(i).score());
            }

            // Iterative refinement (Zoom Optimization)
            double radius = 0.25;
            for (int gen = 1; gen <= totalGenerations && radius >= 0.05; gen++) {
                logger.info("Generation {}/{} (Radius: {})", gen, totalGenerations, radius);
                List<Integer> shuffled = getShuffledIndices(dataPkg.stockCount);
                List<Integer> subset = shuffled.subList(0, Math.max(1, dataPkg.stockCount / 2));
                IntArray gpuSubset = IntArray.fromArray(subset.stream().mapToInt(it -> it).toArray());

                for (int c = 0; c < centersCount; c++) {
                    CandidateResult currentBest = centers.get(c);

                    List<SimulationParams> population = new ArrayList<>();
                    for (int i = 0; i < populationSize; i++) {
                        population.add(i == 0 ? currentBest.params() : fallback.randomize(currentBest.params(), radius));
                    }

                    // Stage 1: Broad discovery on a subset of stocks
                    List<CandidateResult> discovery = evaluateGpu2D(population, gpuSubset, dataPkg, false);
                    List<SimulationParams> elites = discovery.stream()
                            .sorted(Comparator.comparingDouble(CandidateResult::score).reversed())
                            .limit(100)
                            .map(CandidateResult::params)
                            .toList();

                    // Stage 2: Rigorous validation of elites on all stocks
                    IntArray allStockIndices = IntArray.fromArray(java.util.stream.IntStream.range(0, dataPkg.stockCount).toArray());
                    List<CandidateResult> validation = evaluateGpu2D(elites, allStockIndices, dataPkg, false);
                    CandidateResult bestInGeneration = validation.stream()
                            .max(Comparator.comparingDouble(CandidateResult::score))
                            .orElse(currentBest);

                    // Tie-breaking: Update center if score is better OR if score is similar but trade count/gain is higher
                    if (bestInGeneration.score() > currentBest.score() + 0.001 ||
                            (Math.abs(bestInGeneration.score() - currentBest.score()) < 0.001 && bestInGeneration.yearlyGain() > currentBest.yearlyGain())) {
                        centers.set(c, bestInGeneration);
                        logger.info("Center %d Improved: %.2f | AvgReturn: %.2f%%".formatted(
                                c, bestInGeneration.score(), bestInGeneration.yearlyGain()));
                    }

                }
                radius *= 0.85;
            }

            // Final selection and ML sample collection
            CandidateResult globalWinner = centers.stream()
                    .sorted(Comparator.comparingDouble(CandidateResult::score).reversed()
                            .thenComparing(Comparator.comparingDouble(CandidateResult::yearlyGain).reversed()))
                    .findFirst().get();

            logger.info("Selected Global Winner with score: {} | AvgReturn: {}%",
                    globalWinner.score(), globalWinner.yearlyGain());

            // Final pass on CPU to collect ML samples and verify metrics
            fallback.evaluateCandidate(globalWinner.params(), dataPkg, true);

            return globalWinner.params();
        }
    }

    private CandidateResult evaluateCandidate(SimulationParams p, SimulationDataPackage pkg) {
        IntArray allIdx = IntArray.fromArray(java.util.stream.IntStream.range(0, pkg.stockCount).toArray());
        List<CandidateResult> res = evaluateGpu2D(List.of(p), allIdx, pkg, false);
        return (res != null && !res.isEmpty()) ? res.get(0) : new CandidateResult(p, -100.0, 0.0);
    }

    List<CandidateResult> evaluateGpu2D(List<SimulationParams> candidates, IntArray currentSubsetIdx, SimulationDataPackage pkg, boolean rescue) {
        synchronized (TornadoVmOptimizer.class) {
            int populationSize = candidates.size();
            int subsetSize = currentSubsetIdx.getSize();
            int totalDays = pkg.daysCount;
            int gridCount = config.startTimes.size() * config.searchTimes.size() * config.selectTimes.size();
            int batchSize = MAX_BATCH_SIZE;
            List<CandidateResult> resultsList = new ArrayList<>();

            for (int i = 0; i < subsetSize; i++) subsetIndices.set(i, currentSubsetIdx.get(i));

            for (int start = 0; start < populationSize; start += batchSize) {
                int currentBatchSize = Math.min(batchSize, populationSize - start);
                for (int i = 0; i < currentBatchSize; i++) {
                    mapParamsToFloatArray(candidates.get(start + i), parameterMatrix, i * PARAMETER_STRIDE);
                }

                for (int globalIdx = 0; globalIdx < batchSize * subsetSize; globalIdx++) {
                    try {
                        String planId = "unified_s" + subsetSize + "_d" + totalDays + "_b" + currentBatchSize + "_g" + globalIdx;
                        TornadoExecutionPlan plan = planCache.get(planId);
                        if (plan == null) {
                            TaskGraph graph = new TaskGraph(planId)
                                    .transferToDevice(DataTransferMode.EVERY_EXECUTION, parameterMatrix, subsetIndices, simulationGrid)
                                    .transferToDevice(DataTransferMode.FIRST_EXECUTION, technicalData, stockOffsets)
                                    .task(planId + "_task", TornadoVmOptimizer::unifiedKernel,
                                            technicalData, subsetIndices, stockOffsets, parameterMatrix, simulationGrid,
                                            optimizationResults, subsetSize, totalDays, currentBatchSize, gridCount, globalIdx)
                                    .transferToHost(DataTransferMode.EVERY_EXECUTION, optimizationResults);
                            plan = new TornadoExecutionPlan(graph.snapshot());
                            planCache.put(planId, plan);
                        }

                        plan.execute();

                        // Convert to pure Java primitive array once to avoid JNI cross-boundary GC spikes
                        float[] javaResults = optimizationResults.toHeapArray();

                        for (int candidateInBatchIdx = 0; candidateInBatchIdx < currentBatchSize; candidateInBatchIdx++) {
                            double totalTrades = 0, totalExcessReturn = 0, totalSqExcessReturn = 0, totalHoldingDays = 0, totalSumTotalExcess = 0;

                            // Because the grid is computed internally, we just read 5 floats per stock!
                            for (int sIdx = 0; sIdx < subsetSize; sIdx++) {
                                int offset = (candidateInBatchIdx * subsetSize + sIdx) * OPTIMIZATION_RESULT_STRIDE;
                                totalTrades += javaResults[offset];
                                totalHoldingDays += javaResults[offset + 1];
                                totalExcessReturn += javaResults[offset + 2];
                                totalSqExcessReturn += javaResults[offset + 3];
                                totalSumTotalExcess += javaResults[offset + 4];
                            }

                            double score = calculateCandidateScore(totalTrades, totalExcessReturn, totalSqExcessReturn, totalHoldingDays,
                                    subsetSize, gridCount, rescue);

                            double avgReturn = (totalTrades > 0.1) ? (Math.exp(totalSumTotalExcess / totalTrades) - 1.0) * 100.0 : 0.0;
                            resultsList.add(new CandidateResult(candidates.get(start + candidateInBatchIdx), score, avgReturn));
                        }
                    } catch (Exception e) {
                        logger.error("GPU evaluation failed: {}", e.getMessage(), e);
                        List<SimulationParams> failed = candidates.subList(start, populationSize);
                        resultsList.addAll(fallback.evaluateParallel(failed, toList(subsetIndices), pkg, rescue));
                        return resultsList;
                    }
                }
            }
            return resultsList;
        }
    }

    private double calculateCandidateScore(double trades, double excess, double sqExcess, double totalHoldingDays,
                                           int subsetSize, int gridCount, boolean rescue) {
        if (rescue) return -100.0 + trades;

        // Relaxed trade density requirements (Consistent with Simulation.java)
        // Ignore gridCount to avoid constant -100 on small datasets.
        double minRequiredTrades = Math.max(50, (double) subsetSize * gridCount / 1000.0);
        if (trades < minRequiredTrades || trades < 2 || totalHoldingDays < 2.0) return -100.0;

        // excess is sum(dailyExcess * dur) = sum(excessLogRet)
        // sqExcess is sum(dailyExcess^2 * dur)
        // total samples = totalHoldingDays
        double avgDailyExcess = excess / totalHoldingDays;
        double variance = (sqExcess - (excess * excess / totalHoldingDays)) / (totalHoldingDays - 1.000001);
        double stdDev = Math.sqrt(Math.max(0.0, variance));

        // Annualized metrics
        double annualizedExcess = avgDailyExcess * 252.0;
        double annualizedStdDev = stdDev * Math.sqrt(252.0);

        // Aligned smoothing (0.01) with Simulation.java to ensure score parity.
        double sharpe = (annualizedExcess / (annualizedStdDev + 0.01));

        // Resolve Negative Sharpe Paradox: Penalize volatility when returns are negative
        if (annualizedExcess < 0) sharpe = annualizedExcess * (annualizedStdDev + 1.0);

        return sharpe + (trades * 0.000001);
    }

    /**
     * Unified single-pass kernel that performs all calculations for a batch of candidates.
     * 100% Branchless implementation for maximum JIT stability and parity with Simulation.java.
     */
    public static void unifiedKernel(FloatArray technicalData, IntArray subsetIndices, IntArray stockOffsets,
                                     FloatArray parameterMatrix, IntArray simulationGrid,
                                     FloatArray optimizationResults, int subsetSize, int totalDays, int batchSize, int gridCount, int globalIdx) {
        int candidateIdx = globalIdx / subsetSize;
        int localSubsetIdx = globalIdx % subsetSize;

        int globalStockIdx = subsetIndices.get(localSubsetIdx);
        int stockStartOffset = stockOffsets.get(globalStockIdx);
        int paramBase = candidateIdx * PARAMETER_STRIDE;
        int outputIdx = globalIdx * OPTIMIZATION_RESULT_STRIDE;

        // 1. AST SAVER: Zero the recycled memory buffer at the root level
        // (Removes dozens of node operations from the child methods)
        optimizationResults.set(outputIdx, 0.0f);
        optimizationResults.set(outputIdx + 1, 0.0f);
        optimizationResults.set(outputIdx + 2, 0.0f);
        optimizationResults.set(outputIdx + 3, 0.0f);
        optimizationResults.set(outputIdx + 4, 0.0f);

        // 2. AST SAVER: Hoist constants so they aren't compiled into the inner loops
        float totalWeight = parameterMatrix.get(paramBase + 17) + parameterMatrix.get(paramBase + 18) +
                parameterMatrix.get(paramBase + 19) + parameterMatrix.get(paramBase + 20) +
                parameterMatrix.get(paramBase + 21) + parameterMatrix.get(paramBase + 22) +
                parameterMatrix.get(paramBase + 23) + 1e-6f;

        // 3. ELIMINATE THE MIDDLE-MAN: Loop directly here to prevent GraalVM from aggressive inlining
        for (@Parallel int gridTaskIdx = 0; gridTaskIdx < gridCount; gridTaskIdx++) {
            simulateSingleGridTask(technicalData, globalStockIdx, stockStartOffset, totalDays,
                    parameterMatrix, paramBase, totalWeight, simulationGrid, gridTaskIdx,
                    optimizationResults, outputIdx);
        }
    }

    private static void simulateSingleGridTask(FloatArray technicalData, int globalStockIdx, int stockStartOffset, int totalDays,
                                               FloatArray parameterMatrix, int paramBase, float totalWeight,
                                               IntArray simulationGrid, int gridTaskIdx,
                                               FloatArray optimizationResults, int outputIdx) {
        int daysLookback = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE);
        int searchTimeframe = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE + 1);
        int selectionTimeframe = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE + 2);
        int stockBaseIndex = globalStockIdx * totalDays;

        float sellCutoff = parameterMatrix.get(paramBase);
        int longAvgTimeframe = (int) parameterMatrix.get(paramBase + 9);
        float dailyRiskFreeRate = parameterMatrix.get(paramBase + 15);
        float buyThreshold = parameterMatrix.get(paramBase + 16);


        // --- GPU OPTIMIZATION: Branchless Ternaries (Translates to OpenCL select()) ---
        int simStart = totalDays - daysLookback > 0 ? totalDays - daysLookback : 0;
        simStart = simStart > stockStartOffset ? simStart : stockStartOffset;
        float basePSum = (stockStartOffset > 0) ? technicalData.get((globalStockIdx * totalDays + stockStartOffset - 1) * TECH_DATA_STRIDE + 1) : 0.0f;

        int buyLimit = simStart + searchTimeframe < totalDays ? simStart + searchTimeframe : totalDays;

        int finalLimit = selectionTimeframe > 0 ? simStart + searchTimeframe + selectionTimeframe < totalDays ? simStart + searchTimeframe + selectionTimeframe : totalDays : totalDays;

        int tradingState = 0;
        float entryPrice = 0.0f, entryDay = 0.0f;

        float trades = 0, holdingDays = 0, sumExcess = 0, sumSqExcess = 0, sumTotalExcess = 0;

        for (int d = simStart; d < finalLimit; d++) {
            int dayOffset = (stockBaseIndex + d) * TECH_DATA_STRIDE;
            float price = technicalData.get(dayOffset);

            float movingAvg = getMovingAverage(technicalData, stockBaseIndex, d, stockStartOffset, longAvgTimeframe, basePSum, price);
            boolean shouldSkip = calculateShouldSkip(technicalData, globalStockIdx, stockStartOffset, totalDays, parameterMatrix, paramBase, dayOffset, d, price, longAvgTimeframe);

            float heuristic = shouldSkip ? 0.0f : (calcBaseScore(technicalData, parameterMatrix, paramBase, price, movingAvg, dayOffset) +
                    evaluateTradeHeuristic(technicalData, parameterMatrix, paramBase, stockBaseIndex, d, stockStartOffset, basePSum, price)) / totalWeight;


            int doBuy = (tradingState == 0 && d < buyLimit && heuristic > buyThreshold) ? 1 : 0;

            int doSell = (tradingState == 1 && (price < movingAvg * sellCutoff || d == finalLimit - 1)) ? 1 : 0;

            entryPrice = (doBuy == 1) ? price : entryPrice;
            entryDay = (doBuy == 1) ? (float) d : entryDay;
            tradingState = (doBuy == 1) ? 1 : tradingState;

            float dur = (float) d - entryDay;
            float rawRet = (price - entryPrice) / (entryPrice + 1e-6f) - 0.002f;
            rawRet = (rawRet > 2.0f) ? 2.0f : (rawRet < -1.0f ? -1.0f : rawRet);

            float tradeLogRet = (float) Math.log(1.0f + rawRet);
            float excessLogRet = tradeLogRet - (dur * dailyRiskFreeRate);
            float dailyExcess = excessLogRet / (dur > 0.1f ? dur : 1.0f);

            int commit = (doSell == 1 && dur > 0.1f) ? 1 : 0;

            trades = (commit == 1) ? trades + 1.0f : trades;
            holdingDays = (commit == 1) ? holdingDays + dur : holdingDays;
            sumExcess = (commit == 1) ? sumExcess + excessLogRet : sumExcess;
            sumSqExcess = (commit == 1) ? sumSqExcess + (dailyExcess * dailyExcess * dur) : sumSqExcess;
            sumTotalExcess = (commit == 1) ? sumTotalExcess + tradeLogRet : sumTotalExcess;
            tradingState = (doSell == 1) ? 0 : tradingState;

        }

        // Accumulate local timeframe results into the global array at the very end
        optimizationResults.set(outputIdx, optimizationResults.get(outputIdx) + trades);
        optimizationResults.set(outputIdx + 1, optimizationResults.get(outputIdx + 1) + holdingDays);
        optimizationResults.set(outputIdx + 2, optimizationResults.get(outputIdx + 2) + sumExcess);
        optimizationResults.set(outputIdx + 3, optimizationResults.get(outputIdx + 3) + sumSqExcess);
        optimizationResults.set(outputIdx + 4, optimizationResults.get(outputIdx + 4) + sumTotalExcess);
    }

    private static boolean calculateShouldSkip(FloatArray technicalData, int globalStockIdx, int stockStartOffset, int totalDays, FloatArray parameterMatrix, int paramBase, int dayOffset, int d, float price, int longAvgTimeframe) {
        float minMarketCap = parameterMatrix.get(paramBase + 8);
        float maxMarketCap = parameterMatrix.get(paramBase + 14);
        float mMCap = (technicalData.get(dayOffset + 8) >= minMarketCap && technicalData.get(dayOffset + 8) <= maxMarketCap) ? 1.0f : 0.0f;

        int shortLookback = (int) parameterMatrix.get(paramBase + 5);
        int shortIdx = (d - shortLookback > stockStartOffset) ? d - shortLookback : stockStartOffset;
        float oldP = technicalData.get((globalStockIdx * totalDays + shortIdx) * TECH_DATA_STRIDE);
        float mShort = (d >= stockStartOffset + shortLookback && price < oldP * 0.8f) ? 0.0f : 1.0f;
        float mActive = (d - stockStartOffset >= longAvgTimeframe - 1 && price >= 0.05f) ? 1.0f : 0.0f;
        return mActive * mMCap * mShort == 0;
    }

    private static float evaluateTradeHeuristic(FloatArray tech, FloatArray params, int pBase, int stockBaseIndex, int d, int stockStartOffset, float basePSum, float price) {
        int dayOffset = (stockBaseIndex + d) * TECH_DATA_STRIDE;

        float rvol = tech.get(dayOffset + 4) / (tech.get(dayOffset + 5) + 1e-6f);
        float vRvol = Math.max(0.0f, Math.min(1.0f, (rvol - 0.5f) / 1.5f));
        float scoreRVol = vRvol * params.get(pBase + 21);


        float scoreVol = calcVolScore(tech, params, pBase, price, dayOffset);
        float scoreMom = calcMomScore(tech, params, pBase, stockBaseIndex, d, stockStartOffset, basePSum);
        float scorePeg = calcPegScore(tech, params, pBase, stockBaseIndex, d, stockStartOffset, price);

        float rawSum = scoreRVol + scoreVol + scoreMom + scorePeg;

        return (rawSum);
    }

    private static float calcBaseScore(FloatArray tech, FloatArray params, int pBase, float price, float movingAvg, int dayOffset) {
        float gap = price / (movingAvg + 1e-6f);

        float scoreGap = calcGapScore(tech, params, pBase, dayOffset, gap);

        float minR = params.get(pBase + 12);
        float maxR = params.get(pBase + 13);
        float vRating = Math.max(0.0f, Math.min(1.0f, (tech.get(dayOffset + 3) - minR) / (maxR - minR + 1e-6f)));
        float scoreRating = vRating * params.get(pBase + 19);
        float scoreRev = calcRevScore(tech, params, pBase, dayOffset, gap);
        float startingScore = scoreGap + scoreRating + scoreRev;
        return startingScore;
    }

    private static float calcGapScore(FloatArray tech, FloatArray params, int pBase, int dayOffset, float gap) {
        float currentMaxGap = (tech.get(dayOffset + 3) > 4.0f) ? params.get(pBase + 2) * params.get(pBase + 4) : params.get(pBase + 2);
        float vGap = Math.max(0.0f, Math.min(1.0f, (gap - params.get(pBase + 1)) / (currentMaxGap - params.get(pBase + 1) + 1e-6f)));
        float scoreGap = (1.0f - vGap) * params.get(pBase + 17);
        return scoreGap;
    }

    private static float calcRevScore(FloatArray tech, FloatArray params, int pBase, int dayOffset, float gap) {
        float bbP = tech.get(dayOffset + 11);
        float bbFact = (bbP < 0.2f) ? (1.0f - bbP) : 0.5f;
        float meanDev = (gap - 1.0f);
        meanDev = (meanDev < 0 ? -meanDev : meanDev) / 0.20f;
        float scoreRev = Math.max(0.0f, Math.min(1.0f, meanDev)) * bbFact * params.get(pBase + 18);
        return scoreRev;
    }

    private static float calcVolScore(FloatArray tech, FloatArray params, int pBase, float price, int dayOffset) {
        float histVol = tech.get(dayOffset + 2);
        float blendVol = (histVol + (tech.get(dayOffset + 10) / (price + 1e-4f))) / 2.0f;
        float vVol = Math.max(0.0f, Math.min(1.0f, blendVol / 0.05f));
        float scoreVol = (1.0f - vVol) * params.get(pBase + 23);
        return scoreVol;
    }

    private static float getMovingAverage(FloatArray tech, int stockBaseIndex, int d, int stockStartOffset, int longAvgTimeframe, float basePSum, float price) {
        int maStart = Math.max(d - longAvgTimeframe + 1, stockStartOffset);
        float maDivisor = Math.max(1.0f, (float) (d - maStart + 1));

        int currentDayOffset = (stockBaseIndex + d) * TECH_DATA_STRIDE;
        float currentSum = tech.get(currentDayOffset + 1) - basePSum;

        float prevWindowSum = (maStart > stockStartOffset) ? tech.get((stockBaseIndex + maStart - 1) * TECH_DATA_STRIDE + 1) - basePSum : 0.0f;

        float movingAvg = (currentSum - prevWindowSum) / maDivisor;
        return (movingAvg < 0.01f) ? price : movingAvg;
    }

    private static float calcMomScore(FloatArray tech, FloatArray params, int pBase, int stockBaseIndex, int d, int stockStartOffset, float basePSum) {
        int momTf = (int) params.get(pBase + 3);
        float minRate = params.get(pBase + 10);

        int momStartNow = Math.max(d - momTf + 1, stockStartOffset);
        float currentMomSum = tech.get((stockBaseIndex + d) * TECH_DATA_STRIDE + 1) - basePSum;
        float momNowSub = (momStartNow > stockStartOffset) ? (tech.get((stockBaseIndex + momStartNow - 1) * TECH_DATA_STRIDE + 1) - basePSum) : 0.0f;
        float avgPNow = (currentMomSum - momNowSub) / Math.max(1.0f, (float) (d - momStartNow + 1));

        int dLookback = Math.max(d - momTf, stockStartOffset);
        int momStartThen = Math.max(dLookback - momTf + 1, stockStartOffset);
        float lookbackMomSum = tech.get((stockBaseIndex + dLookback) * TECH_DATA_STRIDE + 1) - basePSum;
        float momThenSub = (momStartThen > stockStartOffset) ? (tech.get((stockBaseIndex + momStartThen - 1) * TECH_DATA_STRIDE + 1) - basePSum) : 0.0f;
        float avgPThen = (lookbackMomSum - momThenSub) / Math.max(1.0f, (float) (dLookback - momStartThen + 1));

        float momRatio = (avgPThen > 0) ? avgPNow / avgPThen : 1.0f;
        float macdFactor = (tech.get((stockBaseIndex + d) * TECH_DATA_STRIDE + 9) > 0) ? 1.2f : 0.8f;
        float vMom = Math.max(0.0f, Math.min(1.0f, (momRatio - minRate) / (minRate * 0.3f + 1e-6f)));
        return vMom * macdFactor * params.get(pBase + 20);
    }

    private static float calcPegScore(FloatArray tech, FloatArray params, int pBase, int stockBaseIndex, int d, int stockStartOffset, float price) {
        float epsCurr = tech.get((stockBaseIndex + d) * TECH_DATA_STRIDE + 6);
        float epsHist = (d >= stockStartOffset + 250) ? tech.get((stockBaseIndex + (d - 250)) * TECH_DATA_STRIDE + 6) : 0.0f;
        float epsGrowth = (epsHist > 0.05f && d >= stockStartOffset + 250) ? (epsCurr - epsHist) / epsHist : 0.0f;
        float pegRatio = (epsGrowth > 0.001f && epsCurr > 0.01f) ? (price / epsCurr) / (epsGrowth * 100.0f) : 2.0f;
        float vPeg = Math.max(0.0f, Math.min(1.0f, pegRatio / 2.0f));
        return (1.0f - vPeg) * params.get(pBase + 22);
    }

    private synchronized void preallocateBuffers(int maxStocks, int maxDays) {
        int gridCount = config.startTimes.size() * config.searchTimes.size() * config.selectTimes.size();
        int maxBatchSize = MAX_BATCH_SIZE;

        if (technicalData == null || technicalData.getSize() < maxStocks * maxDays * TECH_DATA_STRIDE) {
            technicalData = new FloatArray(maxStocks * maxDays * TECH_DATA_STRIDE);
        }
        if (parameterMatrix == null || parameterMatrix.getSize() < maxBatchSize * PARAMETER_STRIDE) {
            parameterMatrix = new FloatArray(maxBatchSize * PARAMETER_STRIDE);
        }

        // Minimal footprint buffer (No gridCount multiplier!)
        if (optimizationResults == null || optimizationResults.getSize() < maxBatchSize * maxStocks * OPTIMIZATION_RESULT_STRIDE) {
            optimizationResults = new FloatArray(maxBatchSize * maxStocks * OPTIMIZATION_RESULT_STRIDE);
        }

        if (stockOffsets == null || stockOffsets.getSize() < maxStocks) {
            stockOffsets = new IntArray(maxStocks);
        }
        if (subsetIndices == null || subsetIndices.getSize() < maxStocks) {
            subsetIndices = new IntArray(maxStocks);
        }
        if (simulationGrid == null || simulationGrid.getSize() < gridCount * GRID_TASK_STRIDE) {
            simulationGrid = new IntArray(gridCount * GRID_TASK_STRIDE);
        }

        int[] flatGrid = new int[gridCount * GRID_TASK_STRIDE];
        int gi = 0;
        for (int s : config.startTimes) {
            for (int sr : config.searchTimes) {
                for (int sl : config.selectTimes) {
                    flatGrid[gi++] = s;
                    flatGrid[gi++] = sr;
                    flatGrid[gi++] = sl;
                }
            }
        }
        for (int i = 0; i < flatGrid.length; i++) simulationGrid.set(i, flatGrid[i]);
    }

    private void flattenToTechData(SimulationDataPackage dataPkg) {
        int stocks = dataPkg.stockCount;
        int days = dataPkg.daysCount;
        for (int s = 0; s < stocks; s++) {
            stockOffsets.set(s, dataPkg.offsets[s]);
            for (int d = 0; d < days; d++) {
                int base = (s * days + d) * TECH_DATA_STRIDE;
                technicalData.set(base, (float) dataPkg.closePrices[s][d]);
                technicalData.set(base + 1, (float) dataPkg.pricePrefixSum[s][d]);
                technicalData.set(base + 2, (float) dataPkg.getVolatility(s, d, 20));
                technicalData.set(base + 3, (float) dataPkg.ratings[s][d]);
                technicalData.set(base + 4, (float) dataPkg.volumes[s][d]);
                technicalData.set(base + 5, (float) dataPkg.avgVol30d[s][d]);
                technicalData.set(base + 6, (float) dataPkg.epss[s][d]);
                technicalData.set(base + 7, (float) dataPkg.rsi[s][d]);
                technicalData.set(base + 8, (float) dataPkg.caps[s][d]);
                technicalData.set(base + 9, (float) dataPkg.macd[s][d]);
                technicalData.set(base + 10, (float) dataPkg.atr[s][d]);
                technicalData.set(base + 11, (float) dataPkg.bbP[s][d]);
            }
        }
    }

    private void mapParamsToFloatArray(SimulationParams params, FloatArray array, int offset) {
        array.set(offset, (float) params.sellCutOffPerc());
        array.set(offset + 1, (float) params.lowerPriceToLongAvgBuyIn());
        array.set(offset + 2, (float) params.higherPriceToLongAvgBuyIn());
        array.set(offset + 3, (float) params.timeFrameForUpwardLongAvg());
        array.set(offset + 4, (float) params.aboveAvgRatingPricePerc());
        array.set(offset + 5, (float) params.timeFrameForUpwardShortPrice());
        array.set(offset + 6, (float) params.timeFrameForOscillator());
        array.set(offset + 7, (float) params.maxRSI());
        array.set(offset + 8, (float) params.minMarketCap());
        array.set(offset + 9, (float) params.longMovingAvgTime());
        array.set(offset + 10, (float) params.minRateOfAvgInc());
        array.set(offset + 11, (float) params.maxPERatio());
        array.set(offset + 12, (float) params.minRating());
        array.set(offset + 13, (float) params.maxRating());
        array.set(offset + 14, (float) params.maxMarketCap());
        array.set(offset + 15, (float) (Math.pow(1.0 + params.riskFreeRate(), 1.0 / 252.0) - 1.0));
        array.set(offset + 16, (float) params.buyThreshold());
        array.set(offset + 17, (float) params.movingAvgGapWeight());
        array.set(offset + 18, (float) params.reversionToMeanWeight());
        array.set(offset + 19, (float) params.ratingWeight());
        array.set(offset + 20, (float) params.upwardIncRateWeight());
        array.set(offset + 21, (float) params.rvolWeight());
        array.set(offset + 22, (float) params.pegWeight());
        array.set(offset + 23, (float) params.volatilityCompressionWeight());
    }

    private SimulationParams centerParamsFromConfig() {
        return new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0), config.buyThreshold == null || config.buyThreshold.isEmpty() ? 0.65 : config.buyThreshold.get(0),
                config.movingAvgGapWeight == null || config.movingAvgGapWeight.isEmpty() ? 0.2 : config.movingAvgGapWeight.get(0),
                0.15, 0.2, 0.15, 0.1, 0.1, 0.1
        );
    }

    private List<Integer> toList(IntArray a) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < a.getSize(); i++) l.add(a.get(i));
        return l;
    }

    private List<Integer> getShuffledIndices(int count) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(i);
        Collections.shuffle(list);
        return list;
    }
}
