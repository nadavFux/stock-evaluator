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

        SimulationParams centerParams = new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0)
        );
        double bestScore = evaluate(centerParams, dataPkg, false);
        logger.info("Initial Score: {}", bestScore);

        double radius = 0.2;
        int iterations = 0;
        int populationSize = 50;

        while (radius >= 0.02 && iterations < 10) {
            iterations++;
            logger.info("Starting Generation {} with radius {}", iterations, radius);

            List<CompletableFuture<ParamScore>> futures = new java.util.ArrayList<>();

            // Add current center to prevent regression
            final SimulationParams currentCenter = centerParams;
            final double currentBestScore = bestScore;
            futures.add(CompletableFuture.supplyAsync(() -> new ParamScore(currentCenter, evaluate(currentCenter, dataPkg, false))));

            // Generate N-1 random neighbors
            for (int i = 0; i < populationSize - 1; i++) {
                SimulationParams randParams = randomize(centerParams, radius);
                futures.add(CompletableFuture.supplyAsync(() -> new ParamScore(randParams, evaluate(randParams, dataPkg, false))));
            }

            ParamScore bestInGen = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .max(Comparator.comparingDouble(ParamScore::score))
                            .orElse(new ParamScore(currentCenter, currentBestScore)))
                    .join();

            if (bestInGen.score() > bestScore) {
                bestScore = bestInGen.score();
                centerParams = bestInGen.params();
                logger.info("Generation {} improved best score to {}", iterations, bestScore);
            } else {
                logger.info("Generation {} found no better parameters.", iterations);
            }

            com.stock.analyzer.core.StatsCalculator.clearCache();
            radius *= 0.75; // Zoom in
        }

        logger.info("Optimization complete. Final evaluation and ML data collection...");
        evaluate(centerParams, dataPkg, true);

        return centerParams;
    }

    private double evaluate(SimulationParams params, SimulationDataPackage pkg, boolean collectMLData) {
        Simulation simulation = new Simulation(params);
        int count = 0;
        int tradeCount = 0;
        int stocksProcessed = 0;
        int pruningCheckpoint = pkg.stockCount / 4; // Check after 25%

        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, simulation, timeFrame, collectMLData);

                        // Statistical Pruning
                        stocksProcessed++;
                        if (!collectMLData && stocksProcessed == pruningCheckpoint && stocksProcessed > 10) {
                            double currentScore = simulation.calculateSimulationScore();
                            if (currentScore < -20.0) {
                                return -100.0; // Early exit for bad parameter set
                            }
                        }
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        count++;
                        tradeCount += timeFrame.Trades().size();
                    }
                }
            }
        }
        System.out.println("Finished evaluation. Total trades: " + tradeCount);
        return tradeCount > 1000 ? simulation.calculateSimulationScore() : -100.0;
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

                // Collection for ML
                if (ml && i >= timeStart + 30 && i + 30 < pkg.daysCount) {
                    float[][] sequence = new float[30][12];
                    for (int k = 0; k < 30; k++) {
                        int dayOffset = i - 29 + k;
                        double[] features = sim.extractFeatures(
                                java.util.Arrays.stream(pkg.closePrices[sIdx]).boxed().toList(),
                                java.util.Arrays.asList(new Double[pkg.daysCount]), // Placeholder
                                dayOffset,
                                new Stock(pkg.tickers[sIdx], "name", pkg.tickers[sIdx], "exchange", "date", 0.0f, 0.0, 0.0, "id", "other", 0.0),
                                java.util.Arrays.stream(pkg.epss[sIdx]).boxed().toList(),
                                java.util.Arrays.stream(pkg.ratings[sIdx]).boxed().toList(),
                                java.util.Arrays.stream(pkg.caps[sIdx]).boxed().toList(),
                                java.util.Arrays.stream(pkg.volumes[sIdx]).boxed().toList()
                        );
                        if (features != null) {
                            for (int f = 0; f < 12; f++) sequence[k][f] = (float) features[f];
                        }
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
                clamp(center.sellCutOffPerc() + randomDouble(pctStep), 0.5, 1.0),
                clamp(center.lowerPriceToLongAvgBuyIn() + randomDouble(pctStep), 0.5, 1.0),
                clamp(center.higherPriceToLongAvgBuyIn() + randomDouble(pctStep), 1.0, 1.5),
                clampInt(center.timeFrameForUpwardLongAvg() + randomInt(smallDayStep), 5, 200),
                clamp(center.aboveAvgRatingPricePerc() + randomDouble(pctStep), 0.5, 2.0),
                clampInt(center.timeFrameForUpwardShortPrice() + randomInt(smallDayStep), 1, 50),
                clampInt(center.timeFrameForOscillator() + randomInt(dayStep), 10, 200),
                clamp(center.maxRSI() + randomDouble(20.0 * radius), 0.0, 100.0), // Max +/- 10 points
                minCap,
                clampInt(center.longMovingAvgTime() + randomInt(dayStep), 50, 300),
                clamp(center.minRateOfAvgInc() + randomDouble(pctStep), 0.8, 2.0),
                clampInt(center.maxPERatio() + randomInt((int) (20 * radius)), 0, 100),
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
