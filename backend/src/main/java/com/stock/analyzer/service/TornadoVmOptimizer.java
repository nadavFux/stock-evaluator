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
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.math.TornadoMath;

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
    // Buffer Strides and Layout Constants
    private static final int TECH_DATA_STRIDE = 12;
    private static final int PARAMETER_STRIDE = 24;
    private static final int OPTIMIZATION_RESULT_STRIDE = 5;
    private static final int GRID_TASK_STRIDE = 3;

    private static final int MAX_BATCH_SIZE = 70;

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
            int populationSize = (config.populationSize != null) ? config.populationSize : 280;
            int totalGenerations = (config.generations != null) ? config.generations : 8;


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
            int maxBatchSize = MAX_BATCH_SIZE;
            int maxStocks = pkg.stockCount;
            List<CandidateResult> resultsList = new ArrayList<>();

            for (int i = 0; i < subsetSize; i++) subsetIndices.set(i, currentSubsetIdx.get(i));

            for (int start = 0; start < populationSize; start += maxBatchSize) {
                int currentBatchSize = Math.min(maxBatchSize, populationSize - start);
                for (int i = 0; i < currentBatchSize; i++) {
                    mapParamsToFloatArray(candidates.get(start + i), parameterMatrix, i * PARAMETER_STRIDE);
                }

                try {
                    String planId = "unified_maxS" + maxStocks + "_d" + totalDays + "_maxB" + maxBatchSize + "_g" + gridCount;
                    TornadoExecutionPlan plan = planCache.get(planId);
                    if (plan == null) {
                        TaskGraph graph = new TaskGraph(planId)
                                .transferToDevice(DataTransferMode.EVERY_EXECUTION, parameterMatrix, subsetIndices, simulationGrid)
                                .transferToDevice(DataTransferMode.FIRST_EXECUTION, technicalData, stockOffsets)
                                .task(planId + "_task", TornadoVmOptimizer::unifiedKernel,
                                        technicalData, subsetIndices, stockOffsets, parameterMatrix, simulationGrid,
                                        optimizationResults, maxStocks, totalDays, maxBatchSize, gridCount, maxBatchSize * maxStocks * gridCount,
                                        subsetSize, currentBatchSize)
                                .transferToHost(DataTransferMode.EVERY_EXECUTION, optimizationResults);
                        plan = new TornadoExecutionPlan(graph.snapshot());
                        planCache.put(planId, plan);
                    }
                    plan.execute();

                    // Convert to pure Java primitive array once to avoid JNI cross-boundary GC spikes
                    float[] javaResults = optimizationResults.toHeapArray();

                    for (int candidateInBatchIdx = 0; candidateInBatchIdx < currentBatchSize; candidateInBatchIdx++) {
                        double totalTrades = 0, totalExcessReturn = 0, totalSqExcessReturn = 0, totalHoldingDays = 0, totalSumTotalExcess = 0;

                        // Sum the results across all stocks in the subset and all grid configurations
                        for (int sIdx = 0; sIdx < subsetSize; sIdx++) {
                            for (int gridTaskIdx = 0; gridTaskIdx < gridCount; gridTaskIdx++) {
                                int offset = ((candidateInBatchIdx * maxStocks + sIdx) * gridCount + gridTaskIdx) * OPTIMIZATION_RESULT_STRIDE;
                                totalTrades += javaResults[offset];
                                totalHoldingDays += javaResults[offset + 1];
                                totalExcessReturn += javaResults[offset + 2];
                                totalSqExcessReturn += javaResults[offset + 3];
                                totalSumTotalExcess += javaResults[offset + 4];
                            }
                        }

                        double score = calculateCandidateScore(totalTrades, totalExcessReturn, totalSqExcessReturn, totalHoldingDays,
                                subsetSize, gridCount, rescue);

                        double avgReturn = (totalTrades > 0.1) ? (Math.exp(totalSumTotalExcess / totalTrades) - 1.0) * 100.0 : 0.0;
                        resultsList.add(new CandidateResult(candidates.get(start + candidateInBatchIdx), score, avgReturn));
                    }
                } catch (Exception e) {
                    logger.error("GPU evaluation failed: {}", e.getMessage(), e);
                    List<SimulationParams> failed = candidates.subList(start, populationSize);
                    resultsList.addAll(fallback.evaluateParallel(failed, toList(currentSubsetIdx), pkg, rescue));
                    return resultsList;
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
        if (trades < minRequiredTrades || totalHoldingDays < minRequiredTrades * 2.0) return -100.0;

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
     * 100% Branchless Hot-Path implementation for maximum JIT stability and parity with Simulation.java.
     */
    public static void unifiedKernel(FloatArray technicalData, IntArray subsetIndices, IntArray stockOffsets,
                                     FloatArray parameterMatrix, IntArray simulationGrid,
                                     FloatArray optimizationResults, int maxStocks, int totalDays, int maxBatchSize, int gridCount, int totalElements,
                                     int activeSubsetSize, int activeBatchSize) {

        for (@Parallel int globalIdx = 0; globalIdx < totalElements; globalIdx++) {
            int candidateIdx = globalIdx / (maxStocks * gridCount);
            int rem = globalIdx % (maxStocks * gridCount);
            int localSubsetIdx = rem / gridCount;
            int gridTaskIdx = rem % gridCount;

            int isThreadActive = (candidateIdx < activeBatchSize && localSubsetIdx < activeSubsetSize) ? 1 : 0;

            int safeSubsetIdx = (isThreadActive == 1) ? localSubsetIdx : 0;
            int globalStockIdx = subsetIndices.get(safeSubsetIdx);
            int stockStartOffset = stockOffsets.get(globalStockIdx);
            int paramBase = ((isThreadActive == 1) ? candidateIdx : 0) * PARAMETER_STRIDE;
            int outputIdx = globalIdx * OPTIMIZATION_RESULT_STRIDE;

            optimizationResults.set(outputIdx, 0.0f);
            optimizationResults.set(outputIdx + 1, 0.0f);
            optimizationResults.set(outputIdx + 2, 0.0f);
            optimizationResults.set(outputIdx + 3, 0.0f);
            optimizationResults.set(outputIdx + 4, 0.0f);

            float totalWeight = parameterMatrix.get(paramBase + 17) + parameterMatrix.get(paramBase + 18) +
                    parameterMatrix.get(paramBase + 19) + parameterMatrix.get(paramBase + 20) +
                    parameterMatrix.get(paramBase + 21) + parameterMatrix.get(paramBase + 22) +
                    parameterMatrix.get(paramBase + 23) + 1e-6f;

            float sellCutoff = parameterMatrix.get(paramBase);
            int longAvgTimeframe = (int) parameterMatrix.get(paramBase + 9);
            float dailyRiskFreeRate = parameterMatrix.get(paramBase + 15);
            float buyThreshold = parameterMatrix.get(paramBase + 16);

            int stockBaseIndex = globalStockIdx * totalDays;
            float basePSum = (stockStartOffset > 0) ? technicalData.get((stockBaseIndex + stockStartOffset - 1) * TECH_DATA_STRIDE + 1) : 0.0f;

            // Load parameters for shouldSkip
            float minMarketCap = parameterMatrix.get(paramBase + 8);
            float maxMarketCap = parameterMatrix.get(paramBase + 14);
            int shortLookback = (int) parameterMatrix.get(paramBase + 5);

            // Load parameters for base/heuristic scores
            float maLower = parameterMatrix.get(paramBase + 1);
            float maHigher = parameterMatrix.get(paramBase + 2);
            float aboveAvgRatingPricePerc = parameterMatrix.get(paramBase + 4);
            float minR = parameterMatrix.get(paramBase + 12);
            float maxR = parameterMatrix.get(paramBase + 13);
            
            float gapWeight = parameterMatrix.get(paramBase + 17);
            float reversionWeight = parameterMatrix.get(paramBase + 18);
            float ratingWeight = parameterMatrix.get(paramBase + 19);
            float momWeight = parameterMatrix.get(paramBase + 20);
            float rvolWeight = parameterMatrix.get(paramBase + 21);
            float volWeight = parameterMatrix.get(paramBase + 23);

            int momTf = (int) parameterMatrix.get(paramBase + 3);
            float minRate = parameterMatrix.get(paramBase + 10);

            int gridBase = gridTaskIdx * GRID_TASK_STRIDE;
            int daysLookback = simulationGrid.get(gridBase);
            int searchTimeframe = simulationGrid.get(gridBase + 1);
            int selectionTimeframe = simulationGrid.get(gridBase + 2);

            int simStart = totalDays - daysLookback;
            simStart = (simStart < 0) ? 0 : simStart;
            
            // Match CPU search window logic exactly:
            int buyLimit = simStart + searchTimeframe;
            buyLimit = (buyLimit > totalDays) ? totalDays : buyLimit;

            int finalLimit = totalDays;
            int proposedLimit = simStart + searchTimeframe + selectionTimeframe;
            proposedLimit = (proposedLimit > totalDays) ? totalDays : proposedLimit;
            finalLimit = (selectionTimeframe > 0) ? proposedLimit : totalDays;

            int minStart = stockStartOffset + longAvgTimeframe - 1;

            int tradingState = 0;
            float entryPrice = 0.0f, entryDay = 0.0f, highestPrice = 0.0f;
            float trades = 0, holdingDays = 0, sumExcess = 0, sumSqExcess = 0, sumTotalExcess = 0;

            for (int d = simStart; d < finalLimit; d++) {
                int dayOffset = (stockBaseIndex + d) * TECH_DATA_STRIDE;
                float price = technicalData.get(dayOffset);

                // inline shouldSkip (mActive requires d >= minStart)
                float cap = technicalData.get(dayOffset + 8);
                float condCapMin = (cap >= minMarketCap) ? 1.0f : 0.0f;
                float condCapMax = (cap <= maxMarketCap) ? 1.0f : 0.0f;

                int shortIdx = Math.max(d - shortLookback, stockStartOffset);
                float oldP = technicalData.get((globalStockIdx * totalDays + shortIdx) * TECH_DATA_STRIDE);
                float condShort1 = (d >= stockStartOffset + shortLookback) ? 1.0f : 0.0f;
                float condShort2 = (price < oldP * 0.8f) ? 1.0f : 0.0f;
                float mShort = (condShort1 * condShort2 == 1.0f) ? 0.0f : 1.0f;

                float condPrice = (price >= 0.05f) ? 1.0f : 0.0f;
                float condMinStart = (d >= minStart) ? 1.0f : 0.0f;

                float isActive = condCapMin * condCapMax * mShort * condPrice * condMinStart * (float) isThreadActive;
                int shouldSkip = (int) (1.0f - isActive);

                float heuristic = 0.0f;
                
                // inline getMovingAverage
                int maStart = Math.max(d - longAvgTimeframe + 1, stockStartOffset);
                float maDivisor = Math.max((float) (d - maStart + 1), 1.0f);

                float currentSum = technicalData.get(dayOffset + 1) - basePSum;
                
                // Short-circuit boundary checks in sequential fallback mode using ternaries:
                float prevWindowSum = (maStart > stockStartOffset) ? (technicalData.get((stockBaseIndex + maStart - 1) * TECH_DATA_STRIDE + 1) - basePSum) : 0.0f;
                float movingAvg = (currentSum - prevWindowSum) / maDivisor;
                movingAvg = (movingAvg < 0.01f) ? price : movingAvg;

                float gap = price / (movingAvg + 1e-6f);

                // inline calcGapScore
                float rating = technicalData.get(dayOffset + 3);
                float isGreater = (rating > 4.0f) ? 1.0f : 0.0f;
                float currentMaxGap = isGreater * (maHigher * aboveAvgRatingPricePerc) + (1.0f - isGreater) * maHigher;
                float vGap = (gap - maLower) / (currentMaxGap - maLower + 1e-6f);
                vGap = Math.max(0.0f, Math.min(1.0f, vGap));
                float scoreGap = (1.0f - vGap) * gapWeight;

                // inline calcBaseScore's rating component
                float isGreaterR = (maxR > minR) ? 1.0f : 0.0f;
                float vRating = isGreaterR * ((rating - minR) / (maxR - minR + 1e-6f)) + (1.0f - isGreaterR) * 0.0f;
                vRating = Math.max(0.0f, Math.min(1.0f, vRating));
                float scoreRating = vRating * ratingWeight;

                // inline calcRevScore (fixed reversion fallacy)
                float bbP = technicalData.get(dayOffset + 11);
                float bbFact = (bbP < 0.2f) ? (1.0f - bbP) : 0.5f;
                float isBelow = (gap < 1.0f) ? 1.0f : 0.0f;
                float distFromMA = isBelow * (1.0f - gap) / 0.20f;
                distFromMA = Math.max(0.0f, Math.min(1.0f, distFromMA));
                float scoreRev = distFromMA * bbFact * reversionWeight;

                // inline calcVolScore
                float histVol = technicalData.get(dayOffset + 2);
                float blendVol = (histVol + (technicalData.get(dayOffset + 10) / (price + 1e-4f))) / 2.0f;
                float vVol = blendVol / 0.05f;
                vVol = Math.max(0.0f, Math.min(1.0f, vVol));
                float scoreVol = (1.0f - vVol) * volWeight;

                // inline calcRVolScore
                float rvol = technicalData.get(dayOffset + 4) / (technicalData.get(dayOffset + 5) + 1e-6f);
                float vRvol = (rvol - 0.5f) / 1.5f;
                vRvol = Math.max(0.0f, Math.min(1.0f, vRvol));
                float scoreRVol = vRvol * rvolWeight;

                // inline calcMomScore
                int momStartNow = Math.max(d - momTf + 1, stockStartOffset);
                float currentMomSum = technicalData.get(dayOffset + 1) - basePSum;
                
                // Short-circuit boundary checks in sequential fallback mode using ternaries:
                float momNowSub = (momStartNow > stockStartOffset) ? (technicalData.get((stockBaseIndex + momStartNow - 1) * TECH_DATA_STRIDE + 1) - basePSum) : 0.0f;
                float avgPNow = (currentMomSum - momNowSub) / Math.max(1.0f, (float) (d - momStartNow + 1));

                int hasPrev = (d - momTf >= stockStartOffset) ? 1 : 0;
                int dLookback = hasPrev * (d - momTf) + (1 - hasPrev) * stockStartOffset;
                int momStartThen = Math.max(dLookback - momTf + 1, stockStartOffset);
                float lookbackMomSum = technicalData.get((stockBaseIndex + dLookback) * TECH_DATA_STRIDE + 1) - basePSum;
                
                // Short-circuit boundary checks in sequential fallback mode using ternaries:
                float momThenSub = (momStartThen > stockStartOffset) ? (technicalData.get((stockBaseIndex + momStartThen - 1) * TECH_DATA_STRIDE + 1) - basePSum) : 0.0f;
                float avgPThenVal = (lookbackMomSum - momThenSub) / Math.max(1.0f, (float) (dLookback - momStartThen + 1));
                float avgPThen = (float) hasPrev * avgPThenVal;

                float isAvgPThenGreater = (avgPThen > 0.0f) ? 1.0f : 0.0f;
                float momRatio = isAvgPThenGreater * (avgPNow / (avgPThen + 1e-6f)) + (1.0f - isAvgPThenGreater) * 1.0f;
                float isMacdPos = (technicalData.get(dayOffset + 9) > 0.0f) ? 1.0f : 0.0f;
                float macdFactor = isMacdPos * 1.2f + (1.0f - isMacdPos) * 0.8f;
                float vMom = (momRatio - minRate) / (minRate * 0.3f + 1e-6f);
                vMom = Math.max(0.0f, Math.min(1.0f, vMom));
                float scoreMom = vMom * macdFactor * momWeight;

                float fullHeuristic = (scoreGap + scoreRating + scoreRev + scoreVol + scoreRVol + scoreMom) / totalWeight;
                heuristic = (float) (1 - shouldSkip) * fullHeuristic;

                int condBuy1 = (tradingState == 0) ? 1 : 0;
                int condBuy2 = (d < buyLimit) ? 1 : 0;
                int condBuy3 = (heuristic > buyThreshold) ? 1 : 0;
                int doBuy = condBuy1 * condBuy2 * condBuy3 * isThreadActive;

                int condSell1 = (tradingState == 1) ? 1 : 0;
                int condSell2 = (price < highestPrice * sellCutoff) ? 1 : 0;
                int condSell3 = (d == finalLimit - 1) ? 1 : 0;
                int condSell23 = (condSell2 + condSell3 > 0) ? 1 : 0;
                int doSell = condSell1 * condSell23 * isThreadActive;

                entryPrice = doBuy * price + (1 - doBuy) * entryPrice;
                entryDay = doBuy * (float) d + (1 - doBuy) * entryDay;
                highestPrice = doBuy * price + (1 - doBuy) * Math.max(highestPrice, price);

                float dur = (float) d - entryDay;
                float rawRet = (price - entryPrice) / (entryPrice + 1e-6f) - 0.003f; // Align slippage to 0.003f

                rawRet = Math.max(-1.0f, Math.min(1.0f, rawRet));

                float tradeLogRet = TornadoMath.log(1.0f + rawRet);
                float excessLogRet = tradeLogRet - (dur * dailyRiskFreeRate);
                float safeDur = Math.max(0.1f, dur);
                float dailyExcess = excessLogRet / safeDur;

                int condCommit1 = (doSell == 1) ? 1 : 0;
                int condCommit2 = (dur > 0.1f) ? 1 : 0;
                int commit = condCommit1 * condCommit2;

                trades += (float) commit;
                holdingDays += (float) commit * dur;
                sumExcess += (float) commit * excessLogRet;
                sumSqExcess += (float) commit * (dailyExcess * dailyExcess * dur);
                sumTotalExcess += (float) commit * tradeLogRet;

                tradingState = doBuy * 1 + doSell * 0 + (1 - doBuy - doSell) * tradingState;
            }

            optimizationResults.set(outputIdx, trades);
            optimizationResults.set(outputIdx + 1, holdingDays);
            optimizationResults.set(outputIdx + 2, sumExcess);
            optimizationResults.set(outputIdx + 3, sumSqExcess);
            optimizationResults.set(outputIdx + 4, sumTotalExcess);
        }
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

        int maxResultsSize = maxBatchSize * maxStocks * gridCount * OPTIMIZATION_RESULT_STRIDE;
        if (optimizationResults == null || optimizationResults.getSize() < maxResultsSize) {
            optimizationResults = new FloatArray(maxResultsSize);
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
