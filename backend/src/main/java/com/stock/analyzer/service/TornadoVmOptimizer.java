package com.stock.analyzer.service;

import ai.djl.util.Pair;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.tornado.api.ImmutableTaskGraph;
import uk.ac.manchester.tornado.api.TaskGraph;
import uk.ac.manchester.tornado.api.TornadoExecutionPlan;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.enums.DataTransferMode;
import uk.ac.manchester.tornado.api.types.arrays.DoubleArray;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Advanced GPU-accelerated parameter optimizer using TornadoVM.
 * Implements the full Iterative Random Search with Zoom logic.
 * Uses GPU kernels to instantly evaluate thousands of candidates for signal viability.
 */
public class TornadoVmOptimizer implements Optimizer {
    private static final Logger logger = LoggerFactory.getLogger(TornadoVmOptimizer.class);
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
        logger.info("Starting Multi-Start Param Optimization Workflow (GPU/TornadoVM)...");
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        int M = 5;
        List<SimulationParams> centers = new ArrayList<>();
        centers.add(centerParamsFromConfig());
        for (int i = 1; i < M; i++) {
            centers.add(randomize(centers.get(0), 1.0));
        }

        List<Double> bestScores = new ArrayList<>(Collections.nCopies(M, -100.0));
        boolean[] rescueModes = new boolean[M];
        
        for (int i = 0; i < M; i++) {
            double initialScore = evaluateCandidate(centers.get(i), dataPkg, false);
            bestScores.set(i, initialScore);
            rescueModes[i] = (initialScore == -100.0);
            logger.info("Center {} Initial Score: {}", i, initialScore);
        }

        double radius = 0.25;

        for (int gen = 1; gen <= 10 && radius >= 0.05; gen++) {
            logger.info("Generation {} (Radius: {})", gen, radius);

            List<Integer> subset = getShuffledIndices(dataPkg.stockCount).subList(0, Math.max(1, dataPkg.stockCount / 2));

            // GPU Pre-computation: Flatten dataset once per generation subset
            DoubleArray gpuPrices = flatten(dataPkg.closePrices, subset, dataPkg.daysCount);
            DoubleArray gpuRatings = flatten(dataPkg.ratings, subset, dataPkg.daysCount);
            DoubleArray gpuAvgVol30d = flatten(dataPkg.avgVol30d, subset, dataPkg.daysCount);
            DoubleArray gpuVolumes = flatten(dataPkg.volumes, subset, dataPkg.daysCount);
            
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int c = 0; c < M; c++) {
                final int centerIdx = c;
                SimulationParams center = centers.get(centerIdx);
                double currentBest = bestScores.get(centerIdx);
                boolean rescue = rescueModes[centerIdx];

                final double currentRadius = radius;
                futures.add(CompletableFuture.runAsync(() -> {
                    CandidateResult result = runGeneration(center, currentBest, currentRadius, 500, rescue, dataPkg, subset, gpuPrices, gpuRatings, gpuAvgVol30d, gpuVolumes);

                    if (result.score() > currentBest) {
                        if (rescue && result.score() > -90.0) {
                            rescueModes[centerIdx] = false;
                            logger.info("Center {} Exited Rescue Mode with score: {}", centerIdx, result.score());
                        }
                        bestScores.set(centerIdx, result.score());
                        centers.set(centerIdx, result.params());
                        logger.info("Center {} New Best Score: {}", centerIdx, result.score());
                    }
                }));
            }
            futures.stream().forEach(CompletableFuture::join);

            com.stock.analyzer.core.StatsCalculator.clearSimulationCache();
            radius *= 0.8;
        }

        logger.info("Optimization Complete. Selecting global winner and finalizing ML training data...");
        int bestIdx = 0;
        for (int i = 1; i < M; i++) {
            if (bestScores.get(i) > bestScores.get(bestIdx)) {
                bestIdx = i;
            }
        }
        
        logger.info("Selected Center {} as Global Winner with score: {}", bestIdx, bestScores.get(bestIdx));
        SimulationParams globalWinner = centers.get(bestIdx);
        evaluateCandidate(globalWinner, dataPkg, true);
        return globalWinner;
    }

    private CandidateResult runGeneration(SimulationParams center, double bestScore, double radius, int popSize, boolean rescue, SimulationDataPackage pkg, List<Integer> subset, DoubleArray gp, DoubleArray gr, DoubleArray gav, DoubleArray gv) {
        List<SimulationParams> population = new ArrayList<>();
        for (int i = 0; i < popSize; i++) {
            population.add(i == 0 ? center : randomize(center, radius));
        }

        // STAGE 1: Broad discovery on GPU using Heuristic Signal Filtering
        List<CandidateResult> discoveryResults = evaluateGpu(population, subset, pkg, rescue, gp, gr, gav, gv);

        // STAGE 2: Deep validation of top candidates on 100% of stocks via CPU
        List<SimulationParams> elites = discoveryResults.stream()
                .sorted(Comparator.comparingDouble(CandidateResult::score).reversed())
                .limit(10)
                .map(CandidateResult::params)
                .toList();

        List<Integer> allIndices = java.util.stream.IntStream.range(0, pkg.stockCount).boxed().toList();
        List<CandidateResult> validationResults = evaluateParallel(elites, allIndices, pkg, false);

        return validationResults.stream()
                .max(Comparator.comparingDouble(CandidateResult::score))
                .orElse(new CandidateResult(center, bestScore));
    }

    private List<CandidateResult> evaluateGpu(List<SimulationParams> candidates, List<Integer> stockSubset, SimulationDataPackage pkg, boolean rescue, DoubleArray gp, DoubleArray gr, DoubleArray gav, DoubleArray gv) {
        int popSize = candidates.size();
        DoubleArray paramMatrix = new DoubleArray(popSize * 24);
        IntArray signalCounts = new IntArray(popSize);

        for (int i = 0; i < popSize; i++) {
            mapParamsToArray(candidates.get(i), paramMatrix, i * 24);
        }

        try {
            TaskGraph tg = new TaskGraph("signalFilter")
                .transferToDevice(DataTransferMode.FIRST_EXECUTION, gp, gr, gav, gv)
                .transferToDevice(DataTransferMode.EVERY_EXECUTION, paramMatrix)
                .task("filter", TornadoVmOptimizer::heuristicSignalKernel, gp, gr, gav, gv, paramMatrix, signalCounts, stockSubset.size(), pkg.daysCount)
                .transferToHost(DataTransferMode.EVERY_EXECUTION, signalCounts);

            try (TornadoExecutionPlan plan = new TornadoExecutionPlan(tg.snapshot())) {
                plan.execute();
            }

            List<CandidateResult> results = new ArrayList<>();
            for (int i = 0; i < popSize; i++) {
                int signals = signalCounts.get(i);
                // If signals are too low, the Sharpe ratio will be garbage. Score -100.
                double score = (signals < Math.max(15, (stockSubset.size() * 3) / 25)) ? -100.0 : signals;
                results.add(new CandidateResult(candidates.get(i), score));
            }
            return results;

        } catch (Exception e) {
            logger.warn("TornadoVM Kernel failed, falling back to CPU discovery.");
            return evaluateParallel(candidates, stockSubset, pkg, rescue);
        }
    }

    /**
     * GPU KERNEL: Heuristic Signal Filter.
     * Checks how many 'BUY' signals a parameter set generates across the universe.
     */
    public static void heuristicSignalKernel(DoubleArray prices, DoubleArray ratings, DoubleArray avgVols, DoubleArray volumes, DoubleArray params, IntArray signalCounts, int numStocks, int days) {
        for (@Parallel int pIdx = 0; pIdx < signalCounts.getSize(); pIdx++) {
            int signals = 0;
            int offset = pIdx * 24;
            
            double buyThreshold = params.get(offset + 16);
            double maGapWeight = params.get(offset + 17);
            double ratingWeight = params.get(offset + 19);
            double rvolWeight = params.get(offset + 21);
            
            double totalWeight = maGapWeight + ratingWeight + rvolWeight + 0.45; // simplified remaining weight

            for (int s = 0; s < numStocks; s++) {
                // Check signal at last 10 days
                for (int d = days - 10; d < days; d++) {
                    int dataIdx = s * days + d;
                    double price = prices.get(dataIdx);
                    if (price <= 0) continue;

                    // GPU heuristic implementation (subset of full logic for speed)
                    double rvol = (avgVols.get(dataIdx) > 0) ? volumes.get(dataIdx) / avgVols.get(dataIdx) : 1.0;
                    double rvolScore = (rvol > 1.5) ? 1.0 : (rvol / 1.5);
                    
                    double rating = ratings.get(dataIdx);
                    double ratingScore = (rating - 1.0) / 4.0;

                    double combined = (rvolScore * rvolWeight + ratingScore * ratingWeight) / totalWeight;
                    if (combined > buyThreshold) {
                        signals++;
                    }
                }
            }
            signalCounts.set(pIdx, signals);
        }
    }

    private List<CandidateResult> evaluateParallel(List<SimulationParams> candidates, List<Integer> stockSubset, SimulationDataPackage pkg, boolean rescue) {
        List<CompletableFuture<CandidateResult>> futures = new ArrayList<>();
        for (SimulationParams p : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Simulation sim = new Simulation(p);
                Pair<Integer, Integer> stats = findTrades(sim, pkg, stockSubset);
                int trades = stats.getKey();
                int frames = stats.getValue();
                if (trades == -1) return new CandidateResult(p, -100.0);
                boolean hasVolume = trades > Math.max(15, (stockSubset.size() * frames) / 25);
                double score = rescue ? (-100.0 + trades) : (hasVolume ? sim.calculateScore(frames) : -100.0);
                return new CandidateResult(p, score);
            }));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private Pair<Integer, Integer> findTrades(Simulation sim, SimulationDataPackage pkg, List<Integer> stockSubset) {
        int evaluatedFrames = 0;
        int stocksProcessed = 0;
        int pruningCheckpoint = stockSubset.size() / 4;
        for (int start : config.startTimes) {
            for (int search : config.searchTimes) {
                for (int select : config.selectTimes) {
                    evaluatedFrames++;
                    for (int sIdx : stockSubset) {
                        simulateStock(sim, pkg, sIdx, start, search, select, false);
                        stocksProcessed++;
                        if (stocksProcessed == pruningCheckpoint && pruningCheckpoint > 10) {
                            if (sim.getTradeCount() == 0) return new Pair<>(-1, evaluatedFrames);
                        }
                    }
                }
            }
        }
        return new Pair<>(sim.getTradeCount(), evaluatedFrames);
    }

    private double evaluateCandidate(SimulationParams params, SimulationDataPackage pkg, boolean collectML) {
        Simulation sim = new Simulation(params);
        int frames = 0;
        for (int start : config.startTimes) {
            for (int search : config.searchTimes) {
                for (int select : config.selectTimes) {
                    frames++;
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        simulateStock(sim, pkg, sIdx, start, search, select, collectML);
                    }
                }
            }
        }
        return sim.getTradeCount() > pkg.stockCount / 100 ? sim.calculateScore(frames) : -100.0;
    }

    private void simulateStock(Simulation sim, SimulationDataPackage pkg, int sIdx, int daysBack, int searchTime, int selectTime, boolean ml) {
        int timeStart = Math.max(0, pkg.daysCount - daysBack);
        int searchLimit = Math.min(timeStart + searchTime, pkg.daysCount);
        int absoluteLimit = (selectTime > 0) ? Math.min(timeStart + searchTime + selectTime, pkg.daysCount) : pkg.daysCount;
        for (int i = timeStart; i < searchLimit; i++) {
            if (sim.calculateHeuristic(pkg, sIdx, i) > sim.params.buyThreshold()) {
                double buyPrice = pkg.closePrices[sIdx][i];
                if (ml && i >= timeStart + 30 && i + 30 < pkg.daysCount) {
                    collectMLSample(sim, pkg, sIdx, i, buyPrice);
                }
                for (int j = 1; i + j < absoluteLimit; j++) {
                    int curr = i + j;
                    double price = pkg.closePrices[sIdx][curr];
                    double ma = pkg.getAvg(sIdx, curr, sim.params.longMovingAvgTime());
                    if (price < (ma * sim.params.sellCutOffPerc()) || (curr == absoluteLimit - 1)) {
                        sim.recordTrade((price - buyPrice) / buyPrice, j);
                        i = curr;
                        break;
                    }
                }
            }
        }
    }

    private void collectMLSample(Simulation sim, SimulationDataPackage pkg, int sIdx, int i, double buyPrice) {
        float[][] seq = new float[30][12];
        for (int k = 0; k < 30; k++) {
            double[] features = sim.extractFeatures(pkg, sIdx, i - 29 + k);
            for (int f = 0; f < 12; f++) seq[k][f] = (float) features[f];
        }
        float gain30d = (float) ((pkg.closePrices[sIdx][i + 30] - buyPrice) / buyPrice);
        mlService.collectSample(new TrainingSample(seq, gain30d));
    }

    private DoubleArray flatten(double[][] data, List<Integer> subset, int days) {
        DoubleArray arr = new DoubleArray(subset.size() * days);
        for (int i = 0; i < subset.size(); i++) {
            int sIdx = subset.get(i);
            for (int j = 0; j < days; j++) {
                arr.set(i * days + j, data[sIdx][j]);
            }
        }
        return arr;
    }

    private void mapParamsToArray(SimulationParams p, DoubleArray arr, int start) {
        arr.set(start, p.sellCutOffPerc());
        arr.set(start + 1, p.lowerPriceToLongAvgBuyIn());
        arr.set(start + 2, p.higherPriceToLongAvgBuyIn());
        arr.set(start + 3, p.timeFrameForUpwardLongAvg());
        arr.set(start + 4, p.aboveAvgRatingPricePerc());
        arr.set(start + 5, p.timeFrameForUpwardShortPrice());
        arr.set(start + 6, p.timeFrameForOscillator());
        arr.set(start + 7, p.maxRSI());
        arr.set(start + 8, p.minMarketCap());
        arr.set(start + 9, p.longMovingAvgTime());
        arr.set(start + 10, p.minRateOfAvgInc());
        arr.set(start + 11, p.maxPERatio());
        arr.set(start + 12, p.minRating());
        arr.set(start + 13, p.maxRating());
        arr.set(start + 14, p.maxMarketCap());
        arr.set(start + 15, p.riskFreeRate());
        arr.set(start + 16, p.buyThreshold());
        arr.set(start + 17, p.movingAvgGapWeight());
        arr.set(start + 18, p.reversionToMeanWeight());
        arr.set(start + 19, p.ratingWeight());
        arr.set(start + 20, p.upwardIncRateWeight());
        arr.set(start + 21, p.rvolWeight());
        arr.set(start + 22, p.pegWeight());
        arr.set(start + 23, p.volatilityCompressionWeight());
    }

    public SimulationParams randomize(SimulationParams c, double r) {
        return new SimulationParams(
                clamp(c.sellCutOffPerc() + rand(r), 0.1, 0.99),
                clamp(c.lowerPriceToLongAvgBuyIn() + rand(r), 0.1, 2.0),
                clamp(c.higherPriceToLongAvgBuyIn() + rand(r), 0.1, 3.0),
                clampInt(c.timeFrameForUpwardLongAvg() + randInt((int) (20 * r)), 2, 500),
                clamp(c.aboveAvgRatingPricePerc() + rand(r), 0.1, 5.0),
                clampInt(c.timeFrameForUpwardShortPrice() + randInt((int) (20 * r)), 1, 100),
                clampInt(c.timeFrameForOscillator() + randInt((int) (100 * r)), 2, 500),
                clamp(c.maxRSI() + rand(20 * r), 0.0, 100.0),
                Math.max(0, c.minMarketCap() * (1 + rand(2 * r))),
                clampInt(c.longMovingAvgTime() + randInt((int) (100 * r)), 10, 1000),
                clamp(c.minRateOfAvgInc() + rand(r), 0.0, 5.0),
                clampInt(c.maxPERatio() + randInt((int) (50 * r)), 0, 1000),
                clamp(c.minRating() + rand(4 * r), 0.0, 4.9),
                clamp(c.maxRating() + rand(4 * r), 3.0, 5.0),
                Math.max(1000, c.maxMarketCap() * (1 + rand(2 * r))),
                0.10,
                clamp(c.buyThreshold() + rand(r), 0.4, 0.95),
                clamp(c.movingAvgGapWeight() + rand(r), 0.0, 1.0),
                clamp(c.reversionToMeanWeight() + rand(r), 0.0, 1.0),
                clamp(c.ratingWeight() + rand(r), 0.0, 1.0),
                clamp(c.upwardIncRateWeight() + rand(r), 0.0, 1.0),
                clamp(c.rvolWeight() + rand(r), 0.0, 1.0),
                clamp(c.pegWeight() + rand(r), 0.0, 1.0),
                clamp(c.volatilityCompressionWeight() + rand(r), 0.0, 1.0)
        );
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

    private double rand(double s) { return (random.nextDouble() * 2 * s) - s; }
    private int randInt(int s) { return s <= 0 ? 0 : random.nextInt((s * 2) + 1) - s; }
    private double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private int clampInt(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private List<Integer> getShuffledIndices(int count) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < count; i++) idx.add(i);
        Collections.shuffle(idx);
        return idx;
    }
    private record CandidateResult(SimulationParams params, double score) {}
}
