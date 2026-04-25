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
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * High-Performance Massive 2D Grid GPU Optimizer using TornadoVM.
 * Parallelizes across both Parameter Sets AND Stocks (Over 50,000 threads).
 * Maintains logic and precision parity with the CPU optimizer.
 */
public class TornadoVmOptimizer implements Optimizer {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizer.class);
    private static boolean isAvailable = false;

    static {
        try {
            var runtime = uk.ac.manchester.tornado.api.runtime.TornadoRuntimeProvider.getTornadoRuntime();
            if (runtime != null && runtime.getNumBackends() > 0) {
                logger.info("TornadoVM Hardware Discovery:");
                for (int i = 0; i < runtime.getNumBackends(); i++) {
                    var backend = runtime.getBackend(i);
                    logger.info("  Backend {}: {}", i, backend.getName());
                    for (int j = 0; j < backend.getNumDevices(); j++) {
                        var device = backend.getDevice(j);
                        logger.info("    Device {}: {}", j, device.getDeviceName());
                    }
                }
                isAvailable = true;
            } else {
                logger.warn("TornadoVM runtime loaded but no backends available. Falling back to CPU.");
            }
        } catch (Throwable t) {
            logger.warn("TornadoVM initialization failed. Falling back to CPU: {}", t.getMessage());
        }
    }

    public static boolean isAvailable() {
        return isAvailable;
    }

    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final Random random = new Random();
    private final CpuParamOptimizer fallback;

    public TornadoVmOptimizer(SimulationRangeConfig config) {
        this.config = config;
        this.fallback = new CpuParamOptimizer(config);
    }

    @Override
    public MLModelService getMlService() {
        return mlService;
    }

    @Override
    public SimulationParams optimize(List<StockGraphState> allStocks) {
        logger.info("Starting Massive 2D Grid GPU Optimization Workflow...");
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        int M = 5;
        List<SimulationParams> centers = new ArrayList<>();
        centers.add(centerParamsFromConfig());
        for (int i = 1; i < M; i++) centers.add(randomize(centers.get(0), 1.0));

        logger.info("Flattening stock dataset into GPU segments...");
        DoubleArray gpuPrices = flatten(dataPkg.closePrices, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpuPrefixSums = flatten(dataPkg.pricePrefixSum, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpuSqPrefixSums = flatten(dataPkg.priceSqPrefixSum, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpuRatings = flatten(dataPkg.ratings, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpuVolumes = flatten(dataPkg.volumes, dataPkg.stockCount, dataPkg.daysCount);
        DoubleArray gpuAvgVols = flatten(dataPkg.avgVol30d, dataPkg.stockCount, dataPkg.daysCount);
        IntArray gpuOffsets = IntArray.fromArray(dataPkg.offsets);

        List<Double> bestScores = new ArrayList<>(Collections.nCopies(M, -100.0));
        boolean[] rescueModes = new boolean[M];

        for (int i = 0; i < M; i++) {
            logger.info("Evaluating Initial Center {}/{} on GPU...", i + 1, M);
            double initialScore = evaluateCandidate(centers.get(i), dataPkg, false, gpuPrices, gpuPrefixSums, gpuSqPrefixSums, gpuRatings, gpuVolumes, gpuAvgVols, gpuOffsets);
            bestScores.set(i, initialScore);
            rescueModes[i] = (initialScore <= -90.0);
            logger.info("Center {} Initial Score: {}", i, initialScore);
        }

        double radius = 0.25;
        for (int gen = 1; gen <= 10 && radius >= 0.05; gen++) {
            logger.info("Generation {} (Radius: {})", gen, radius);

            List<Integer> subsetIndices = getShuffledIndices(dataPkg.stockCount).subList(0, Math.max(1, dataPkg.stockCount / 2));
            IntArray gpuSubset = toIntArray(subsetIndices);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int c = 0; c < M; c++) {
                final int centerIdx = c;
                final SimulationParams center = centers.get(centerIdx);
                final boolean rescue = rescueModes[centerIdx];
                final double currentRadius = radius;

                futures.add(CompletableFuture.runAsync(() -> {
                    CandidateResult result = runGeneration(center, bestScores.get(centerIdx), currentRadius, 500, rescue, dataPkg, gpuSubset,
                            gpuPrices, gpuPrefixSums, gpuSqPrefixSums, gpuRatings, gpuVolumes, gpuAvgVols, gpuOffsets);

                    if (result.score() > bestScores.get(centerIdx)) {
                        if (rescue && result.score() > -90.0) rescueModes[centerIdx] = false;
                        bestScores.set(centerIdx, result.score());
                        centers.set(centerIdx, result.params());
                    }
                }));
            }
            futures.stream().forEach(CompletableFuture::join);
            com.stock.analyzer.core.StatsCalculator.clearSimulationCache();
            radius *= 0.8;
        }

        SimulationParams globalWinner = centers.get(bestScores.indexOf(Collections.max(bestScores)));
        logger.info("Finalizing global winner on CPU...");
        fallback.evaluateCandidate(globalWinner, dataPkg, true);
        return globalWinner;
    }

    private double evaluateCandidate(SimulationParams params, SimulationDataPackage pkg, boolean collectML,
                                     DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav, IntArray go) {
        int[] allIndices = new int[pkg.stockCount];
        for (int i = 0; i < pkg.stockCount; i++) allIndices[i] = i;
        IntArray allStocksSubset = IntArray.fromArray(allIndices);

        List<CandidateResult> results = evaluateGpu2D(List.of(params), allStocksSubset, pkg, false, gp, gps, gpsq, gr, gv, gav, go);
        return results.get(0).score();
    }

    private CandidateResult runGeneration(SimulationParams center, double bestScore, double radius, int popSize, boolean rescue, SimulationDataPackage pkg, IntArray subset,
                                          DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav, IntArray go) {
        List<SimulationParams> population = new ArrayList<>();
        for (int i = 0; i < popSize; i++) population.add(i == 0 ? center : randomize(center, radius));

        List<CandidateResult> discoveryResults = evaluateGpu2D(population, subset, pkg, rescue, gp, gps, gpsq, gr, gv, gav, go);

        List<SimulationParams> elites = discoveryResults.stream()
                .sorted(Comparator.comparingDouble(CandidateResult::score).reversed())
                .limit(10).map(CandidateResult::params).toList();

        List<CandidateResult> validationResults = fallback.evaluateParallel(elites, java.util.stream.IntStream.range(0, pkg.stockCount).boxed().toList(), pkg, false);
        return validationResults.stream().max(Comparator.comparingDouble(CandidateResult::score)).orElse(new CandidateResult(center, bestScore));
    }

    private List<CandidateResult> evaluateGpu2D(List<SimulationParams> candidates, IntArray subset, SimulationDataPackage pkg, boolean rescue,
                                                DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav, IntArray go) {
        int popSize = candidates.size();
        int numStocks = subset.getSize();
        int days = pkg.daysCount;
        
        DoubleArray paramMatrix = new DoubleArray(popSize * 24);
        DoubleArray heuristicScores = new DoubleArray(popSize * numStocks * days);
        DoubleArray resultsBuffer = new DoubleArray(popSize * numStocks * 4);
        
        for (int i = 0; i < popSize; i++) mapParamsToArray(candidates.get(i), paramMatrix, i * 24);

        List<Integer> cStarts = config.startTimes;
        List<Integer> cSearches = config.searchTimes;
        List<Integer> cSelects = config.selectTimes;

        int gridCount = cStarts.size() * cSearches.size() * cSelects.size();
        int[] flatGrid = new int[gridCount * 3];
        int gridIdx = 0;
        for (int s : cStarts) {
            for (int sr : cSearches) {
                for (int sl : cSelects) {
                    flatGrid[gridIdx++] = s;
                    flatGrid[gridIdx++] = sr;
                    flatGrid[gridIdx++] = sl;
                }
            }
        }
        IntArray gpuGrid = IntArray.fromArray(flatGrid);

        DoubleArray indMaG = new DoubleArray(numStocks * days);
        DoubleArray indRvol = new DoubleArray(numStocks * days);
        DoubleArray indRat = new DoubleArray(numStocks * days);
        DoubleArray indVolat = new DoubleArray(numStocks * days);
        DoubleArray indMom = new DoubleArray(numStocks * days);

        try {
            runFullPipeline(gp, gps, gpsq, gr, gv, gav, go, subset, 
                            indMaG, indRvol, indRat, indVolat, indMom,
                            heuristicScores, paramMatrix, gpuGrid, resultsBuffer, popSize, days, candidates.get(0).longMovingAvgTime());

            List<CandidateResult> out = new ArrayList<>();
            for (int p = 0; p < popSize; p++) {
                int totalTrades = 0;
                double totalSumRet = 0, totalSumSqRet = 0;
                long totalHold = 0;

                for (int s = 0; s < numStocks; s++) {
                    int off = (p * numStocks + s) * 4;
                    totalTrades += (int) resultsBuffer.get(off);
                    totalSumRet += resultsBuffer.get(off + 1);
                    totalSumSqRet += resultsBuffer.get(off + 2);
                    totalHold += (long) resultsBuffer.get(off + 3);
                }
                
                boolean hasVolume = totalTrades > Math.max(15, (numStocks * gridCount) / 25);
                double score;
                if (rescue) {
                    score = -100.0 + totalTrades;
                } else if (candidates.size() > 1 && !hasVolume) {
                    score = -100.0;
                } else if (totalTrades < 2) {
                    score = -100.0;
                } else {
                    score = calculateSharpeOnHost(totalTrades, totalSumRet, totalSumSqRet, totalHold, candidates.get(p).riskFreeRate(), gridCount, rescue);
                }
                out.add(new CandidateResult(candidates.get(p), score));
            }
            return out;
        } catch (Exception e) {
            logger.warn("GPU Pipeline failed, falling back to CPU: {}", e.getMessage());
            return fallback.evaluateParallel(candidates, toList(subset), pkg, rescue);
        }
    }

    private void runFullPipeline(DoubleArray gp, DoubleArray gps, DoubleArray gpsq, DoubleArray gr, DoubleArray gv, DoubleArray gav, IntArray go, IntArray gs,
                                 DoubleArray gMaG, DoubleArray grvS, DoubleArray graS, DoubleArray gVol, DoubleArray gMom, DoubleArray gHS, DoubleArray params, 
                                 IntArray gpuGrid, DoubleArray results, int popSize, int days, int longMA) {
        
        TaskGraph tg = new TaskGraph("full_gpu")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, gp, gps, gpsq, gr, gv, gav, go, gs, gpuGrid)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, params)
                .task("indicators", TornadoVmOptimizer::indicatorKernel, gp, gps, gpsq, gr, gv, gav, go, gs, gMaG, grvS, graS, gVol, gMom, days, longMA)
                .task("scoring", TornadoVmOptimizer::heuristicKernel, gMaG, grvS, graS, gVol, gMom, gs, params, gHS, popSize, days)
                .task("simulation", TornadoVmOptimizer::simulationKernel, gp, gHS, gMaG, gs, params, gpuGrid, results, popSize, days)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, results);

        try (TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot())) {
            plan.execute();
        } catch (uk.ac.manchester.tornado.api.exceptions.TornadoExecutionPlanException e) {
            logger.error("GPU Pipeline execution failed", e);
            throw new RuntimeException(e);
        }
    }

    public static void simulationKernel(DoubleArray prices, DoubleArray heuristicScores, DoubleArray maGaps, IntArray subset, DoubleArray params,
                                        IntArray gridData, DoubleArray results, int popSize, int days) {
        int numStocks = subset.getSize();
        int numGridPoints = gridData.getSize() / 3;
        int totalThreads = popSize * numStocks * numGridPoints;

        for (@Parallel int threadIdx = 0; threadIdx < totalThreads; threadIdx++) {
            int pIdx = threadIdx / (numStocks * numGridPoints);
            int sLoc = (threadIdx / numGridPoints) % numStocks;
            int gIdx = threadIdx % numGridPoints;
            
            int sIdx = subset.get(sLoc);
            int pOff = pIdx * 24;
            double buyThreshold = params.get(pOff + 16);
            double sellCutOff = params.get(pOff); 
            
            int daysBack = gridData.get(gIdx * 3);
            int searchTime = gridData.get(gIdx * 3 + 1);
            int selectTime = gridData.get(gIdx * 3 + 2);

            int timeStart = Math.max(0, days - daysBack);
            int searchLimit = Math.min(days, timeStart + searchTime);
            int absoluteLimit = (selectTime > 0) ? Math.min(days, timeStart + searchTime + selectTime) : days;

            double trades = 0.0;
            double sumRet = 0.0;
            double sumSqRet = 0.0;
            double totalHold = 0.0;
            int skipUntil = -1;

            for (int i = 0; i < days; i++) {
                int scoreIdx = (pIdx * numStocks * days) + (sLoc * days) + i;
                int isEntryPossible = (i >= timeStart && i < searchLimit && i > skipUntil) ? 1 : 0;
                if (isEntryPossible == 1) {
                    if (heuristicScores.get(scoreIdx) > buyThreshold) {
                        double buyPrice = prices.get(sIdx * days + i);
                        int sellDay = absoluteLimit - 1;
                        int exitFound = 0;
                        for (int j = 1; j < 500; j++) {
                            int curr = i + j;
                            if (curr < absoluteLimit && exitFound == 0) {
                                if (maGaps.get((sLoc * days) + curr) < sellCutOff) {
                                    sellDay = curr;
                                    exitFound = 1;
                                }
                            }
                        }
                        double exitPrice = prices.get(sIdx * days + sellDay);
                        double ret = (exitPrice - buyPrice) / buyPrice * 100.0;
                        trades += 1.0;
                        sumRet += ret;
                        sumSqRet += (ret * ret);
                        totalHold += (double)(sellDay - i);
                        skipUntil = sellDay;
                    }
                }
            }

            int resOff = threadIdx * 4;
            results.set(resOff, trades);
            results.set(resOff + 1, sumRet);
            results.set(resOff + 2, sumSqRet);
            results.set(resOff + 3, totalHold);
        }
    }

    public static void indicatorKernel(DoubleArray prices, DoubleArray prefixSums, DoubleArray sqPrefixSums, DoubleArray ratings, DoubleArray volumes, DoubleArray avgVols, IntArray offsets, IntArray subset,
                                       DoubleArray maGaps, DoubleArray rvolScores, DoubleArray ratingScores, DoubleArray volatilities, DoubleArray momentums, int days, int period) {
        int subsetSize = subset.getSize();
        for (@Parallel int idx = 0; idx < subsetSize * days; idx++) {
            int sLoc = idx / days; int d = idx % days;
            int sIdx = subset.get(sLoc); int off = offsets.get(sIdx);
            int dataIdx = sIdx * days + d;
            double price = prices.get(dataIdx);
            double isValid = (price > 0.0 && d >= off) ? 1.0 : 0.0;

            int startIdx = Math.max(off - 1, d - period);
            int actualPeriod = Math.max(1, d - startIdx);
            double sum = prefixSums.get(dataIdx);
            double prevSum = (startIdx >= off) ? prefixSums.get(sIdx * days + startIdx) : 0.0;
            double ma = (sum - prevSum) / (double) actualPeriod;
            maGaps.set(idx, isValid * (price / (ma + 0.0001)));

            int vStartIdx = Math.max(off - 1, d - 20);
            int vActualPeriod = Math.max(1, d - vStartIdx);
            double vSqSum = sqPrefixSums.get(dataIdx);
            double vPrevSqSum = (vStartIdx >= off) ? sqPrefixSums.get(sIdx * days + vStartIdx) : 0.0;
            double vVar = ((vSqSum - vPrevSqSum) / (double) vActualPeriod) - (ma * ma);
            volatilities.set(idx, isValid * (Math.sqrt(Math.max(0.0, vVar)) / (price + 0.0001)));

            int mStartIdx = Math.max(off - 1, d - 40);
            int mPrevStartIdx = Math.max(off - 1, d - 80);
            double mSumNow = prefixSums.get(dataIdx);
            double mSumPrevVal = (mStartIdx >= off) ? prefixSums.get(sIdx * days + mStartIdx) : 0.0;
            double mAvgNow = (mSumNow - mSumPrevVal) / (double) Math.max(1, d - mStartIdx);
            double mSumOldPrevVal = (mPrevStartIdx >= off) ? prefixSums.get(sIdx * days + mPrevStartIdx) : 0.0;
            double mAvgOld = (mSumPrevVal - mSumOldPrevVal) / (double) Math.max(1, mStartIdx - mPrevStartIdx);
            momentums.set(idx, isValid * (mAvgOld > 0.0 ? mAvgNow / mAvgOld : 1.0));

            double currentAvgVol = avgVols.get(dataIdx);
            rvolScores.set(idx, isValid * ((currentAvgVol > 0.0) ? (volumes.get(dataIdx) / currentAvgVol) : 1.0));
            ratingScores.set(idx, isValid * ((ratings.get(dataIdx) - 1.0) / 4.0));
        }
    }

    public static void heuristicKernel(DoubleArray maGaps, DoubleArray rvolScores, DoubleArray ratingScores, DoubleArray volatilities, DoubleArray momentums, IntArray subset, 
                                       DoubleArray params, DoubleArray heuristicScores, int popSize, int days) {
        int numStocks = subset.getSize();
        for (@Parallel int idx = 0; idx < popSize * numStocks * days; idx++) {
            int pIdx = idx / (numStocks * days); int sLoc = (idx / days) % numStocks; int d = idx % days;
            int pOff = pIdx * 24; int indicatorIdx = sLoc * days + d;

            double buyThreshold = params.get(pOff + 16);
            double maW = params.get(pOff + 17); double revW = params.get(pOff + 18);
            double ratW = params.get(pOff + 19); double incW = params.get(pOff + 20);
            double rvolW = params.get(pOff + 21); double pegW = params.get(pOff + 22);
            double volW = params.get(pOff + 23);
            double totalW = maW + revW + ratW + incW + rvolW + pegW + volW;

            double maGap = maGaps.get(indicatorIdx);
            double maScore = (1.0 - normalizeGPU(maGap, params.get(pOff + 1), params.get(pOff + 2))) * (maW / totalW);
            double scoreSoFar = maScore;
            double remainingW = (totalW - maW) / totalW;
            
            if (scoreSoFar + remainingW < buyThreshold) {
                heuristicScores.set(idx, 0.0);
            } else {
                scoreSoFar += normalizeGPU(ratingScores.get(indicatorIdx), (params.get(pOff + 12) - 1.0) / 4.0, (params.get(pOff + 13) - 1.0) / 4.0) * (ratW / totalW);
                remainingW -= (ratW / totalW);
                if (scoreSoFar + remainingW >= buyThreshold) {
                    scoreSoFar += normalizeGPU(rvolScores.get(indicatorIdx), 0.5, 2.0) * (rvolW / totalW);
                    remainingW -= (rvolW / totalW);
                    if (scoreSoFar + remainingW >= buyThreshold) {
                        scoreSoFar += normalizeGPU(Math.abs(maGap - 1.0), 0.0, 0.20) * (revW / totalW);
                        remainingW -= (revW / totalW);
                        if (scoreSoFar + remainingW >= buyThreshold) {
                            scoreSoFar += normalizeGPU(momentums.get(indicatorIdx), params.get(pOff + 10), params.get(pOff + 10) * 1.3) * (incW / totalW);
                            remainingW -= (incW / totalW);
                            if (scoreSoFar + remainingW >= buyThreshold) {
                                scoreSoFar += (1.0 - normalizeGPU(volatilities.get(indicatorIdx), 0.0, 0.05)) * (volW / totalW);
                                heuristicScores.set(idx, scoreSoFar);
                            } else heuristicScores.set(idx, 0.0);
                        } else heuristicScores.set(idx, 0.0);
                    } else heuristicScores.set(idx, 0.0);
                } else heuristicScores.set(idx, 0.0);
            }
        }
    }

    private static double normalizeGPU(double val, double min, double max) {
        if (max <= min) return 1.0;
        return Math.max(0.0, Math.min(1.0, (val - min) / (max - min)));
    }

    private double calculateSharpeOnHost(int trades, double sumRet, double sumSqRet, long totalDays, double riskFree, int frames, boolean rescue) {
        if (trades < 2) return rescue ? -100.0 + trades : -100.0;
        double avg = sumRet / trades;
        double var = (sumSqRet / (trades - 1)) - (avg * avg * trades / (trades - 1));
        double std = Math.sqrt(Math.max(0, var));
        double sharpe = (avg / (std + 0.01)) * Math.sqrt(252);
        double volMult = Math.sqrt(Math.min(1.0, (double) trades / 40.0) * Math.min(1.0, (double) trades / frames / 10.0));
        double durMult = Math.min(1.0, ((double) totalDays / trades) / 5.0);
        return sharpe * volMult * durMult * 10.0;
    }

    private List<Integer> toList(IntArray arr) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < arr.getSize(); i++) l.add(arr.get(i));
        return l;
    }

    private IntArray toIntArray(List<Integer> list) {
        return IntArray.fromArray(list.stream().mapToInt(Integer::intValue).toArray());
    }

    private DoubleArray flatten(double[][] data, int stocks, int days) {
        double[] flat = new double[stocks * days];
        for (int i = 0; i < stocks; i++) System.arraycopy(data[i], 0, flat, i * days, days);
        return DoubleArray.fromArray(flat);
    }

    private void mapParamsToArray(SimulationParams p, DoubleArray arr, int start) {
        arr.set(start, p.sellCutOffPerc()); arr.set(start + 1, p.lowerPriceToLongAvgBuyIn());
        arr.set(start + 2, p.higherPriceToLongAvgBuyIn()); arr.set(start + 3, p.timeFrameForUpwardLongAvg());
        arr.set(start + 4, p.aboveAvgRatingPricePerc()); arr.set(start + 5, p.timeFrameForUpwardShortPrice());
        arr.set(start + 6, p.timeFrameForOscillator()); arr.set(start + 7, p.maxRSI());
        arr.set(start + 8, p.minMarketCap()); arr.set(start + 9, p.longMovingAvgTime());
        arr.set(start + 10, p.minRateOfAvgInc()); arr.set(start + 11, p.maxPERatio());
        arr.set(start + 12, p.minRating()); arr.set(start + 13, p.maxRating());
        arr.set(start + 14, p.maxMarketCap()); arr.set(start + 15, p.riskFreeRate());
        arr.set(start + 16, p.buyThreshold()); arr.set(start + 17, p.movingAvgGapWeight());
        arr.set(start + 18, p.reversionToMeanWeight()); arr.set(start + 19, p.ratingWeight());
        arr.set(start + 20, p.upwardIncRateWeight()); arr.set(start + 21, p.rvolWeight());
        arr.set(start + 22, p.pegWeight()); arr.set(start + 23, p.volatilityCompressionWeight());
    }

    public SimulationParams randomize(SimulationParams c, double r) {
        return fallback.randomize(c, r);
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

    private List<Integer> getShuffledIndices(int count) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < count; i++) idx.add(i);
        Collections.shuffle(idx);
        return idx;
    }
}
