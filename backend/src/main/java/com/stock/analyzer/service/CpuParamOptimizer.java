package com.stock.analyzer.service;

import ai.djl.util.Pair;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Intelligent parameter optimizer using Iterative Random Search with Zoom Optimization.
 * Uses CPU-based multithreaded evaluation with statistical pruning.
 */
public class CpuParamOptimizer implements Optimizer {
    private static final Logger logger = LoggerFactory.getLogger(CpuParamOptimizer.class);
    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final Random random = new Random();

    public CpuParamOptimizer(SimulationRangeConfig config) {
        this.config = config;
    }

    @Override
    public MLModelService getMlService() {
        return mlService;
    }

    /**
     * Executes the full optimization lifecycle.
     */
    @Override
    public SimulationParams optimize(List<StockGraphState> allStocks) {
        logger.info("Starting Multi-Start Param Optimization Workflow (CPU)...");
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

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int c = 0; c < M; c++) {
                final int centerIdx = c;
                SimulationParams center = centers.get(centerIdx);
                double currentBest = bestScores.get(centerIdx);
                boolean rescue = rescueModes[centerIdx];

                final double currentRadius = radius;
                futures.add(CompletableFuture.runAsync(() -> {
                    CandidateResult result = runGeneration(center, currentBest, currentRadius, 500, rescue, dataPkg, subset);

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

    private CandidateResult runGeneration(SimulationParams center, double bestScore, double radius, int popSize, boolean rescue, SimulationDataPackage pkg, List<Integer> subset) {
        List<SimulationParams> population = new ArrayList<>();
        for (int i = 0; i < popSize; i++) {
            population.add(i == 0 ? center : randomize(center, radius));
        }

        List<CandidateResult> discoveryResults = evaluateParallel(population, subset, pkg, rescue);

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

    List<CandidateResult> evaluateParallel(List<SimulationParams> candidates, List<Integer> stockSubset, SimulationDataPackage pkg, boolean rescue) {
        List<CompletableFuture<CandidateResult>> futures = new ArrayList<>();
        for (SimulationParams p : candidates) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Simulation sim = new Simulation(p);
                Pair<Integer, Integer> stats = findTrades(sim, pkg, stockSubset);

                int trades = stats.getKey();
                int frames = stats.getValue();

                if (trades == -1) return new CandidateResult(p, -100.0);

                // Enforce minimum trade density requirements
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
                            if (sim.getTradeCount() == 0) {
                                return new Pair<>(-1, evaluatedFrames);
                            }
                        }
                    }
                }
            }
        }
        return new Pair<>(sim.getTradeCount(), evaluatedFrames);
    }

    double evaluateCandidate(SimulationParams params, SimulationDataPackage pkg, boolean collectML) {
        Simulation sim = new Simulation(params);
        if (sim.params == null) return -100.0;

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

                // Hold period simulation
                for (int j = 1; j < absoluteLimit - i; j++) {
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

    private double rand(double s) {
        return (random.nextDouble() * 2 * s) - s;
    }

    private int randInt(int s) {
        return s <= 0 ? 0 : random.nextInt((s * 2) + 1) - s;
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private List<Integer> getShuffledIndices(int count) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < count; i++) idx.add(i);
        Collections.shuffle(idx);
        return idx;
    }
}
