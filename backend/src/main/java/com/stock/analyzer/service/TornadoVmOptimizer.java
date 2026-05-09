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
import uk.ac.manchester.tornado.api.math.TornadoMath;
import uk.ac.manchester.tornado.api.types.arrays.FloatArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;

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
    private static boolean isAvailable = false;

    // Buffer Strides and Layout Constants
    private static final int TECH_DATA_STRIDE = 12;
    private static final int PARAMETER_STRIDE = 24;
    private static final int OPTIMIZATION_RESULT_STRIDE = 4;
    private static final int GRID_TASK_STRIDE = 3;

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
            logger.error("TornadoVM initialization failed: {}", t.getMessage());
        }
    }

    public static boolean isAvailable() {
        return isAvailable;
    }

    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final CpuParamOptimizer fallback;

    // Reusable GPU buffers to minimize allocation overhead
    private FloatArray technicalData;
    private FloatArray parameterMatrix;
    private FloatArray optimizationResults;
    private IntArray stockOffsets;
    private IntArray subsetIndices;
    private IntArray simulationGrid;

    private final Map<String, TornadoExecutionPlan> planCache = new HashMap<>();

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
        logger.info("Starting GPU Multi-Start Optimization (Refined Architecture)...");
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        preallocateBuffers(dataPkg.stockCount, dataPkg.daysCount);
        flattenToTechData(dataPkg);

        // Initialize multiple starting points (centers) for the search
        int centersCount = (config.centersCount != null) ? config.centersCount : 6;
        int populationSize = (config.populationSize != null) ? config.populationSize : 600;
        int totalGenerations = (config.generations != null) ? config.generations : 7;

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
                        .limit(10)
                        .map(CandidateResult::params)
                        .toList();

                // Stage 2: Rigorous validation of elites on all stocks
                IntArray allStockIndices = IntArray.fromArray(java.util.stream.IntStream.range(0, dataPkg.stockCount).toArray());
                List<CandidateResult> validation = evaluateGpu2D(elites, allStockIndices, dataPkg, false);
                CandidateResult bestInGeneration = validation.stream()
                        .max(Comparator.comparingDouble(CandidateResult::score))
                        .orElse(currentBest);

                if (bestInGeneration.score() > currentBest.score()) {
                    centers.set(c, bestInGeneration);
                    logger.info("Center %d Improved: %.2f | Yearly Gain: %.2f%% (vs RF: %.2f%%)".formatted(
                            c, bestInGeneration.score(), bestInGeneration.yearlyGain(), 
                            bestInGeneration.params().riskFreeRate() * 100.0));
                }

            }
            radius *= 0.8;
        }

        // Final selection and ML sample collection
        CandidateResult globalWinner = centers.stream().max(Comparator.comparingDouble(CandidateResult::score)).get();
        logger.info("Selected Global Winner with score: {}", globalWinner.score());
        fallback.evaluateCandidate(globalWinner.params(), dataPkg, true);

        planCache.clear();
        return globalWinner.params();
    }

    private CandidateResult evaluateCandidate(SimulationParams p, SimulationDataPackage pkg) {
        IntArray allIdx = IntArray.fromArray(java.util.stream.IntStream.range(0, pkg.stockCount).toArray());
        List<CandidateResult> res = evaluateGpu2D(List.of(p), allIdx, pkg, false);
        return (res != null && !res.isEmpty()) ? res.get(0) : new CandidateResult(p, -100.0, 0.0);
    }

    List<CandidateResult> evaluateGpu2D(List<SimulationParams> candidates, IntArray currentSubsetIdx, SimulationDataPackage pkg, boolean rescue) {
        // Ensure buffers are allocated for direct calls (e.g. from tests)
        preallocateBuffers(pkg.stockCount, pkg.daysCount);

        int populationSize = candidates.size();
        int subsetSize = currentSubsetIdx.getSize();
        int totalStocks = pkg.stockCount;
        int totalDays = pkg.daysCount;
        int gridCount = config.startTimes.size() * config.searchTimes.size() * config.selectTimes.size();

        int batchSize = 50;
        List<CandidateResult> resultsList = new ArrayList<>();

        for (int i = 0; i < subsetSize; i++) subsetIndices.set(i, currentSubsetIdx.get(i));

        for (int start = 0; start < populationSize; start += batchSize) {
            int currentBatchSize = Math.min(batchSize, populationSize - start);
            for (int i = 0; i < currentBatchSize; i++) {
                mapParamsToFloatArray(candidates.get(start + i), parameterMatrix, i * PARAMETER_STRIDE);
            }

            try {
                String planId = "unified_s" + subsetSize + "_g" + gridCount;
                TornadoExecutionPlan plan = planCache.get(planId);
                if (plan == null) {
                    TaskGraph graph = new TaskGraph(planId)
                            .transferToDevice(DataTransferMode.EVERY_EXECUTION, parameterMatrix, subsetIndices, simulationGrid)
                            .transferToDevice(DataTransferMode.FIRST_EXECUTION, technicalData, stockOffsets)
                            .task(planId + "_task", TornadoVmOptimizer::unifiedKernel,
                                    technicalData, subsetIndices, stockOffsets, parameterMatrix, simulationGrid,
                                    optimizationResults, subsetSize, totalDays, currentBatchSize, gridCount)
                            .transferToHost(DataTransferMode.EVERY_EXECUTION, optimizationResults);
                    plan = new TornadoExecutionPlan(graph.snapshot());
                    planCache.put(planId, plan);
                }
                plan.execute();

                for (int candidateInBatchIdx = 0; candidateInBatchIdx < currentBatchSize; candidateInBatchIdx++) {
                    double totalTrades = 0, totalDaysHolding = 0, totalExcessReturn = 0, totalSqExcessReturn = 0;
                    for (int sIdx = 0; sIdx < subsetSize; sIdx++) {
                        for (int gIdx = 0; gIdx < gridCount; gIdx++) {
                            int offset = (candidateInBatchIdx * subsetSize * gridCount + sIdx * gridCount + gIdx) * OPTIMIZATION_RESULT_STRIDE;
                            totalTrades += optimizationResults.get(offset);
                            totalDaysHolding += optimizationResults.get(offset + 1);
                            totalExcessReturn += optimizationResults.get(offset + 2);
                            totalSqExcessReturn += optimizationResults.get(offset + 3);
                        }
                    }

                    double avgExcess = totalTrades > 0 ? totalExcessReturn / totalTrades : 0;
                    double riskFreeRateYearly = candidates.get(start + candidateInBatchIdx).riskFreeRate() * 100.0;
                    double yearlyGain = (avgExcess + (riskFreeRateYearly / 252.0)) * 252.0;

                    double score = calculateCandidateScore(candidates.get(start + candidateInBatchIdx),
                            totalTrades, totalDaysHolding, totalExcessReturn, totalSqExcessReturn,
                            subsetSize, gridCount, rescue);
                    resultsList.add(new CandidateResult(candidates.get(start + candidateInBatchIdx), score, yearlyGain));
                }
            } catch (Exception e) {
                logger.error("GPU evaluation failed: {}", e.getMessage());
                List<SimulationParams> failed = candidates.subList(start, populationSize);
                resultsList.addAll(fallback.evaluateParallel(failed, toList(subsetIndices), pkg, rescue));
                return resultsList;
            }
        }
        return resultsList;
    }

    private double calculateCandidateScore(SimulationParams params, double trades, double days, double excess, double sqExcess,
                                           int subsetSize, int gridCount, boolean rescue) {
        if (rescue) return -100.0 + trades;

        // Enforce minimum trade density requirements (Same as CPU)
        boolean hasSufficientVolume = trades > Math.max(15, (subsetSize * gridCount) / 25.0);
        if (!hasSufficientVolume || trades < 2) return -100.0;

        double avgExcess = excess / trades;
        double variance = (sqExcess - (excess * excess / trades)) / (trades - 1.000001);
        double stdDev = Math.sqrt(Math.max(0.0, variance));
        double sharpeRatio = (avgExcess / (stdDev + 0.01)) * Math.sqrt(DAYS_PER_YEAR);

        // Multipliers for statistical reliability
        double activityFactor = Math.min(1.0, trades / 40.0);
        // Density factor should be relative to total simulation runs (subsetSize * gridCount)
        // Target: 1 trade per 10 runs (10% density)
        double densityFactor = Math.min(1.0, (trades / (float) (subsetSize * gridCount)) / 0.1);
        double volumeConsistency = Math.sqrt(activityFactor * densityFactor);
        double durationMultiplier = Math.min(1.0, (days / (trades + 0.000001)) / 5.0);

        return sharpeRatio * volumeConsistency * durationMultiplier * 10.0;
    }

    /**
     * Unified single-pass kernel that performs all calculations for a batch of candidates.
     */
    public static void unifiedKernel(FloatArray technicalData, IntArray subsetIndices, IntArray stockOffsets,
                                     FloatArray parameterMatrix, IntArray simulationGrid, FloatArray optimizationResults,
                                     int subsetSize, int totalDays, int currentBatchSize, int gridCount) {

        for (@Parallel int globalIdx = 0; globalIdx < currentBatchSize * subsetSize * gridCount; globalIdx++) {
            int candidateIdx = globalIdx / (subsetSize * gridCount);
            int localSubsetIdx = (globalIdx / gridCount) % subsetSize;
            int gridTaskIdx = globalIdx % gridCount;

            int globalStockIdx = subsetIndices.get(localSubsetIdx);
            int stockStartOffset = stockOffsets.get(globalStockIdx);
            int paramBase = candidateIdx * PARAMETER_STRIDE;

            // Load candidate parameters into fast registers
            float sellCutoff = parameterMatrix.get(paramBase);
            float lowInGap = parameterMatrix.get(paramBase + 1);
            float highInGap = parameterMatrix.get(paramBase + 2);
            int momentumTimeframe = (int) parameterMatrix.get(paramBase + 3);
            float aboveAvgMultiplier = parameterMatrix.get(paramBase + 4);
            float maxRSI = parameterMatrix.get(paramBase + 7);
            float minMarketCap = parameterMatrix.get(paramBase + 8);
            int longAvgTimeframe = (int) parameterMatrix.get(paramBase + 9);
            float minRateInc = parameterMatrix.get(paramBase + 10);
            float minRatingParam = parameterMatrix.get(paramBase + 12);
            float maxRatingParam = parameterMatrix.get(paramBase + 13);
            float maxMarketCap = parameterMatrix.get(paramBase + 14);
            float dailyRiskFreeRate = parameterMatrix.get(paramBase + 15);
            float buyThreshold = parameterMatrix.get(paramBase + 16);

            float weightGap = parameterMatrix.get(paramBase + 17);
            float weightRev = parameterMatrix.get(paramBase + 18);
            float weightRating = parameterMatrix.get(paramBase + 19);
            float weightMomentum = parameterMatrix.get(paramBase + 20);
            float weightRVol = parameterMatrix.get(paramBase + 21);
            float weightPEG = parameterMatrix.get(paramBase + 22);
            float weightVolatility = parameterMatrix.get(paramBase + 23);
            float totalWeight = weightGap + weightRev + weightRating + weightMomentum + weightRVol + weightPEG + weightVolatility + (float) EPSILON;

            // Simulation grid parameters
            int daysLookback = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE);
            int searchTimeframe = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE + 1);
            int selectionTimeframe = simulationGrid.get(gridTaskIdx * GRID_TASK_STRIDE + 2);

            int simStart = (totalDays - daysLookback > 0) ? totalDays - daysLookback : 0;
            int buyLimit = (simStart + searchTimeframe < totalDays) ? simStart + searchTimeframe : totalDays;
            int finalLimit = (selectionTimeframe > 0) ? ((simStart + searchTimeframe + selectionTimeframe < totalDays) ? simStart + searchTimeframe + selectionTimeframe : totalDays) : totalDays;

            float trades = 0, holdingDays = 0, sumExcess = 0, sumSqExcess = 0;
            int tradingState = 0; // 0: Searching, 1: Holding
            float entryPrice = 0.0f, entryDay = 0.0f;

            for (int d = simStart; d < finalLimit; d++) {
                if (d < stockStartOffset) continue;

                int dayOffset = (globalStockIdx * totalDays + d) * TECH_DATA_STRIDE;
                float price = technicalData.get(dayOffset);
                if (price <= 0) continue;

                // 1. Calculate Integrated Heuristic
                int maStart = (d - longAvgTimeframe + 1 > stockStartOffset) ? d - longAvgTimeframe + 1 : stockStartOffset;
                float maDivisor = (float) (d - maStart + 1);
                float movingAvg = (technicalData.get(dayOffset + 1) - (maStart > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (maStart - 1)) * TECH_DATA_STRIDE + 1) : 0.0f)) / (maDivisor > 0 ? maDivisor : 1.0f);
                float gap = price / (movingAvg + (float) EPSILON);

                // Gap Score
                float currentMaxGap = (technicalData.get(dayOffset + 3) > 4.0f) ? highInGap * aboveAvgMultiplier : highInGap;
                float vGap = (gap - lowInGap) / (currentMaxGap - lowInGap + (float) EPSILON);
                float scoreGap = (1.0f - (vGap < 0 ? 0 : (vGap > 1 ? 1 : vGap))) * (weightGap / totalWeight);

                // Rating Score
                float rating = technicalData.get(dayOffset + 3);
                float scoreRating = TornadoMath.clamp((rating - minRatingParam) / (maxRatingParam - minRatingParam + (float) EPSILON), 0, 1) * (weightRating / totalWeight);

                // RVol Score
                float rvol = technicalData.get(dayOffset + 4) / (technicalData.get(dayOffset + 5) + (float) EPSILON);
                float scoreRVol = TornadoMath.clamp((rvol - 0.5f) / 1.5f, 0, 1) * (weightRVol / totalWeight);

                // Momentum Score (with MACD multiplier)
                int momentumStartNow = (d - momentumTimeframe + 1 > stockStartOffset) ? d - momentumTimeframe + 1 : stockStartOffset;
                float avgPriceNow = (technicalData.get(dayOffset + 1) - (momentumStartNow > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (momentumStartNow - 1)) * TECH_DATA_STRIDE + 1) : 0.0f)) / (float) (d - momentumStartNow + 1);
                int dayLookbackMomentum = (d - momentumTimeframe > stockStartOffset) ? d - momentumTimeframe : stockStartOffset;
                int momentumStartThen = (dayLookbackMomentum - momentumTimeframe + 1 > stockStartOffset) ? dayLookbackMomentum - momentumTimeframe + 1 : stockStartOffset;
                float avgPriceThen = (technicalData.get((globalStockIdx * totalDays + dayLookbackMomentum) * TECH_DATA_STRIDE + 1) - (momentumStartThen > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (momentumStartThen - 1)) * TECH_DATA_STRIDE + 1) : 0.0f)) / (float) (dayLookbackMomentum - momentumStartThen + 1);
                float momentumRatio = (avgPriceThen > 0) ? avgPriceNow / avgPriceThen : 1.0f;
                float macdFactor = (technicalData.get(dayOffset + 9) > 0) ? 1.2f : 0.8f;
                float normalizedMomentum = (momentumRatio - minRateInc) / (minRateInc * 0.3f + (float) EPSILON);
                float scoreMom = TornadoMath.clamp(normalizedMomentum, 0, 1) * macdFactor * (weightMomentum / totalWeight);

                // Reversion Score (with BB%P multiplier)
                float bollingerBandP = technicalData.get(dayOffset + 11);
                float bollingerFactor = (bollingerBandP < 0.2f) ? (1.0f - bollingerBandP) : 0.5f;
                float meanDeviation = (gap - 1.0f);
                meanDeviation = (meanDeviation < 0 ? -meanDeviation : meanDeviation) / 0.20f;
                float scoreRev = (meanDeviation < 0 ? 0 : (meanDeviation > 1 ? 1 : meanDeviation)) * bollingerFactor * (weightRev / totalWeight);

                // PEG Score
                float epsCurrent = technicalData.get(dayOffset + 6);
                float epsHistorical = (d >= stockStartOffset + 250) ? technicalData.get((globalStockIdx * totalDays + (d - 250)) * TECH_DATA_STRIDE + 6) : 0.0f;
                float epsGrowthRate = (epsCurrent - epsHistorical) / (epsHistorical + (float) EPSILON);
                float dynamicPegRatio = (epsGrowthRate > 0 && epsCurrent > 0) ? (price / epsCurrent) / (epsGrowthRate * 100.0f) : 2.0f;
                float scorePeg = (1.0f - TornadoMath.clamp(dynamicPegRatio / 2.0f, 0, 1)) * (weightPEG / totalWeight);

                // Volatility Score (ATR-Refined)
                int volWindow = 20;
                int volStart = (d - volWindow + 1 > stockStartOffset) ? d - volWindow + 1 : stockStartOffset;
                float volCount = (float) (d - volStart + 1);
                float avgPriceVol = (technicalData.get(dayOffset + 1) - (volStart > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (volStart - 1)) * TECH_DATA_STRIDE + 1) : 0.0f)) / (volCount > 0 ? volCount : 1.0f);
                float avgSqPriceVol = (technicalData.get(dayOffset + 2) - (volStart > stockStartOffset ? technicalData.get((globalStockIdx * totalDays + (volStart - 1)) * TECH_DATA_STRIDE + 2) : 0.0f)) / (volCount > 0 ? volCount : 1.0f);
                float historicalVol = TornadoMath.sqrt(TornadoMath.max(0, avgSqPriceVol - (avgPriceVol * avgPriceVol))) / (price + 0.0001f);
                float blendedVol = (historicalVol + (technicalData.get(dayOffset + 10) / (price + 0.0001f))) / 2.0f;
                float scoreVol = (1.0f - TornadoMath.clamp(blendedVol / 0.05f, 0, 1)) * (weightVolatility / totalWeight);

                float marketCap = technicalData.get(dayOffset + 8);
                float mcFilter = (marketCap <= 0 || (marketCap >= minMarketCap && marketCap <= maxMarketCap)) ? 1.0f : 0.0f;
                float rsiFilter = (technicalData.get(dayOffset + 7) <= maxRSI) ? 1.0f : 0.0f;

                float heuristic = (scoreGap + scoreRating + scoreRVol + scoreMom + scoreRev + scorePeg + scoreVol) * mcFilter * rsiFilter;

                // 2. Perform Simulation Step
                int triggerBuy = (tradingState == 0 && d < buyLimit && heuristic > buyThreshold) ? 1 : 0;
                int triggerSell = (tradingState == 1 && (price < movingAvg * sellCutoff || d == finalLimit - 1)) ? 1 : 0;

                if (triggerBuy == 1) {
                    entryPrice = price;
                    entryDay = (float) d;
                    tradingState = 1;
                } else if (triggerSell == 1) {
                    float duration = (float) d - entryDay;
                    if (duration > 0) {
                        float rawGain = (price - entryPrice) / (entryPrice + (float) EPSILON) * 100.0f;
                        float excessReturn = (rawGain - (dailyRiskFreeRate * duration * 100.0f)) / duration;

                        trades += 1.0f;
                        holdingDays += duration;
                        sumExcess += excessReturn;
                        sumSqExcess += excessReturn * excessReturn;
                    }
                    tradingState = 0;
                }
            }

            int outputIdx = globalIdx * OPTIMIZATION_RESULT_STRIDE;
            optimizationResults.set(outputIdx, trades);
            optimizationResults.set(outputIdx + 1, holdingDays);
            optimizationResults.set(outputIdx + 2, sumExcess);
            optimizationResults.set(outputIdx + 3, sumSqExcess);
        }
    }

    private void preallocateBuffers(int maxStocks, int maxDays) {
        int gridCount = config.startTimes.size() * config.searchTimes.size() * config.selectTimes.size();
        int maxBatchSize = 50;

        if (technicalData == null || technicalData.getSize() < maxStocks * maxDays * TECH_DATA_STRIDE) {
            technicalData = new FloatArray(maxStocks * maxDays * TECH_DATA_STRIDE);
        }
        if (parameterMatrix == null || parameterMatrix.getSize() < maxBatchSize * PARAMETER_STRIDE) {
            parameterMatrix = new FloatArray(maxBatchSize * PARAMETER_STRIDE);
        }
        if (optimizationResults == null || optimizationResults.getSize() < maxBatchSize * maxStocks * gridCount * OPTIMIZATION_RESULT_STRIDE) {
            optimizationResults = new FloatArray(maxBatchSize * maxStocks * gridCount * OPTIMIZATION_RESULT_STRIDE);
        }
        if (stockOffsets == null || stockOffsets.getSize() < maxStocks) {
            stockOffsets = new IntArray(maxStocks);
        }
        if (subsetIndices == null || subsetIndices.getSize() < maxStocks) {
            subsetIndices = new IntArray(maxStocks);
        }
        if (simulationGrid == null || simulationGrid.getSize() < gridCount * GRID_TASK_STRIDE) {
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
            simulationGrid = IntArray.fromArray(flatGrid);
        }
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
                technicalData.set(base + 2, (float) dataPkg.priceSqPrefixSum[s][d]);
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
