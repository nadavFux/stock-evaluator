package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

public class ParamOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(ParamOptimizer.class);
    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();
    private final Random random = new Random();

    public ParamOptimizer(SimulationRangeConfig config) {
        this.config = config;
    }

    public MLModelService getMlService() {
        return mlService;
    }

    public SimulationParams optimize(List<StockGraphState> allStocks) {
        logger.info("Starting Iterative Random Search with Zoom Optimization...");
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        SimulationParams centerParams = centerParamsFromConfig();
        double bestScore = evaluate(centerParams, dataPkg, false);
        logger.info("Initial Score: {}", bestScore);

        boolean rescueMode = (bestScore == -100.0);
        if (rescueMode) logger.info("Rescue Mode Active: Optimization will prioritize finding trades over Sharpe Ratio.");

        double radius = 0.2;
        int maxIterations = 10;
        int populationSize = 300;

        for (int iter = 1; iter <= maxIterations && radius >= 0.02; iter++) {
            logger.info("Starting Generation {} with radius {}", iter, radius);

            ParamScore result = runGeneration(centerParams, bestScore, radius, populationSize, rescueMode, dataPkg);

            if (result.score() > bestScore) {
                if (rescueMode && result.score() > -90.0) {
                    rescueMode = false;
                    logger.info("Generation {} exited Rescue Mode with score {}", iter, result.score());
                } else {
                    logger.info("Generation {} improved best score from {} to {}", iter, bestScore, result.score());
                }
                bestScore = result.score();
                centerParams = result.params();
            } else {
                logger.info("Generation {} found no better parameters. Center score: {}", iter, bestScore);
            }

            com.stock.analyzer.core.StatsCalculator.clearCache();
            radius *= 0.75; // Zoom in
        }

        logger.info("Optimization complete. Final evaluation and ML data collection...");
        evaluate(centerParams, dataPkg, true);
        return centerParams;
    }

    private SimulationParams centerParamsFromConfig() {
        return new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0)
        );
    }

    private ParamScore runGeneration(SimulationParams center, double bestScoreSoFar, double radius, int popSize, boolean rescue, SimulationDataPackage pkg) {
        final java.util.concurrent.atomic.AtomicReference<ParamScore> bestInGen = 
                new java.util.concurrent.atomic.AtomicReference<>(new ParamScore(center, bestScoreSoFar));
        
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < popSize; i++) {
            final int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                SimulationParams p = generateCandidate(center, radius, idx, rescue, bestInGen.get().score());
                Simulation sim = new Simulation(p);
                int trades = evaluateRaw(p, pkg, sim);
                double score = rescue ? (-100.0 + trades) : (trades > 10 ? sim.calculateSimulationScore() : -100.0);
                
                bestInGen.accumulateAndGet(new ParamScore(p, score), (old, next) -> next.score() > old.score() ? next : old);
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return bestInGen.get();
    }

    private SimulationParams generateCandidate(SimulationParams center, double radius, int idx, boolean rescue, double genBestScore) {
        if (idx == 0) return center;
        if (rescue && genBestScore == -100.0) return uniformRandom();
        return randomize(center, radius);
    }

    private int evaluateRaw(SimulationParams params, SimulationDataPackage pkg, Simulation simulation) {
        int tradeCount = 0;
        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, simulation, timeFrame, false);
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        tradeCount += timeFrame.Trades().size();
                    }
                }
            }
        }
        return tradeCount;
    }

    private double evaluate(SimulationParams params, SimulationDataPackage pkg, boolean collectMLData) {
        Simulation simulation = new Simulation(params);
        int tradeCount = 0;
        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, simulation, timeFrame, collectMLData);
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        tradeCount += timeFrame.Trades().size();
                    }
                }
            }
        }
        
        // Final sanity check for ML collection: if we still have no samples, force a pass over all data
        if (collectMLData && mlService.getSampleCount() < 100) {
            for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                // Wide window for ML data collection
                fastSimulate(pkg, sIdx, pkg.daysCount - 60, pkg.daysCount - 60, simulation, new StocksTradeTimeFrame(0,0,0), true);
            }
        }
        
        return tradeCount > 10 ? simulation.calculateSimulationScore() : -100.0;
    }

    private void fastSimulate(SimulationDataPackage pkg, int sIdx, int daysBack, int searchTime, Simulation sim, StocksTradeTimeFrame tf, boolean ml) {
        int timeStart = Math.max(0, pkg.daysCount - daysBack);
        int searchLimit = Math.min(timeStart + searchTime, pkg.daysCount);

        for (int i = timeStart; i < searchLimit; i++) {
            SimulationResult res = sim.calculateFastScore(pkg, sIdx, i);
            if (res.heuristicScore() > 0.65) {
                double buyPrice = pkg.closePrices[sIdx][i];
                double buyMA = pkg.getAvg(sIdx, i, sim.params.longMovingAvgTime());
                if (buyMA == 0) continue;
                double cutOff = (buyPrice / buyMA) * sim.params.sellCutOffPerc();

                // Collection for ML - Optimized to use SimulationDataPackage
                if (ml && i >= timeStart + 30 && i + 30 < pkg.daysCount) {
                    float[][] sequence = new float[30][12];
                    for (int k = 0; k < 30; k++) {
                        int dayOffset = i - 29 + k;
                        double[] features = sim.extractFeaturesFast(pkg, sIdx, dayOffset);
                        for (int f = 0; f < 12; f++) sequence[k][f] = (float) features[f];
                    }

                    double priceIn30Days = pkg.closePrices[sIdx][i + 30];
                    float gain30d = (float) ((priceIn30Days - buyPrice) / buyPrice);
                    mlService.collectSample(new TrainingSample(sequence, gain30d));
                }

                for (int j = 1; j < searchLimit - i; j++) {
                    double currentPrice = pkg.closePrices[sIdx][i + j];
                    double currentMA = pkg.getAvg(sIdx, i + j, sim.params.longMovingAvgTime());
                    // Realistic execution: exit at stop-loss OR at the end of the simulation period
                    if (currentPrice < (currentMA * cutOff) || (j == searchLimit - i - 1)) {
                        double exitPrice = currentPrice;
                        double gain = (exitPrice - buyPrice) / buyPrice;
                        tf.AddRow(new StockTrade(pkg.tickers[sIdx], gain, daysBack - i + timeStart, j, buyPrice / buyMA, pkg.caps[sIdx][i], pkg.dates[sIdx][i]));

                        i += (j - 1);
                        break;
                    }
                }
            }
        }
    }

    public SimulationParams uniformRandom() {
        double minCap = 1000.0 + random.nextDouble() * 100000000.0;
        double maxCap = minCap + 1000000.0 + random.nextDouble() * 10000000000.0;
        double minRating = random.nextDouble() * 4.9;
        double maxRating = minRating + 0.1 + random.nextDouble() * (5.0 - minRating - 0.1);

        return new SimulationParams(
                0.5 + random.nextDouble() * 0.5,
                0.1 + random.nextDouble() * 0.9,
                1.0 + random.nextDouble() * 0.5,
                5 + random.nextInt(195),
                0.5 + random.nextDouble() * 1.5,
                1 + random.nextInt(49),
                10 + random.nextInt(190),
                random.nextDouble() * 100.0,
                minCap,
                50 + random.nextInt(250),
                0.8 + random.nextDouble() * 1.2,
                random.nextInt(100),
                minRating,
                maxRating,
                maxCap,
                0.10
        );
    }

    public SimulationParams randomize(SimulationParams center, double radius) {
        double pctStep = radius;
        int dayStep = (int) Math.max(1, 100 * radius);
        int smallDayStep = (int) Math.max(1, 20 * radius);
        double capStepPerc = 2 * radius;
        double ratingStep = 4 * radius;

        double minCap = Math.max(0.0, center.minMarketCap() * (1.0 + randomDouble(capStepPerc)));
        double maxCap = Math.max(minCap + 1.0, center.maxMarketCap() * (1.0 + randomDouble(capStepPerc)));

        double minRating = clamp(center.minRating() + randomDouble(ratingStep), 0.0, 4.9);
        double maxRating = clamp(center.maxRating() + randomDouble(ratingStep), minRating + 0.1, 5.0);

        return new SimulationParams(
                clamp(center.sellCutOffPerc() + randomDouble(pctStep), 0.1, 2.0),
                clamp(center.lowerPriceToLongAvgBuyIn() + randomDouble(pctStep), 0.1, 2.0),
                clamp(center.higherPriceToLongAvgBuyIn() + randomDouble(pctStep), 0.1, 3.0),
                clampInt(center.timeFrameForUpwardLongAvg() + randomInt(smallDayStep), 2, 500),
                clamp(center.aboveAvgRatingPricePerc() + randomDouble(pctStep), 0.1, 5.0),
                clampInt(center.timeFrameForUpwardShortPrice() + randomInt(smallDayStep), 1, 100),
                clampInt(center.timeFrameForOscillator() + randomInt(dayStep), 2, 500),
                clamp(center.maxRSI() + randomDouble(20.0 * radius), 0.0, 100.0),
                minCap,
                clampInt(center.longMovingAvgTime() + randomInt(dayStep), 10, 1000),
                clamp(center.minRateOfAvgInc() + randomDouble(pctStep), 0.0, 5.0),
                clampInt(center.maxPERatio() + randomInt((int) (50 * radius)), 0, 1000),
                minRating,
                maxRating,
                maxCap,
                0.10
        );
    }

    private double randomDouble(double maxStep) {
        return (random.nextDouble() * 2 * maxStep) - maxStep;
    }

    private int randomInt(int maxStep) {
        if (maxStep <= 0) return 0;
        return random.nextInt((maxStep * 2) + 1) - maxStep;
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private record ParamScore(SimulationParams params, double score) {
    }
}
