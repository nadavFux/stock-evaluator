package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StatsCalculator;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ParamOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(ParamOptimizer.class);
    private final SimulationRangeConfig config;
    private final MLModelService mlService = new MLModelService();

    public ParamOptimizer(SimulationRangeConfig config) {
        this.config = config;
    }

    public MLModelService getMlService() {
        return mlService;
    }

    public SimulationParams optimize(List<StockGraphState> allStocks) {
        logger.info("Starting Parallel Coordinate Descent Optimization...");

        SimulationParams currentParams = new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0)
        );
        double bestScore = evaluate(currentParams, allStocks);
        logger.info("Initial Score: {}", bestScore);

        boolean improved = true;
        int iterations = 0;
        while (improved && iterations < 3) {
            improved = false;
            iterations++;
            logger.info("Starting Parallel Optimization Iteration {}", iterations);

            currentParams = findBestInParallel(allStocks, currentParams, config.sellCutOffPerc, (p, v) -> p.toBuilder().sellCutOffPerc(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.lowerPriceToLongAvgBuyIn, (p, v) -> p.toBuilder().lowerPriceToLongAvgBuyIn(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.higherPriceToLongAvgBuyIn, (p, v) -> p.toBuilder().higherPriceToLongAvgBuyIn(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.timeFrameForUpwardLongAvg, (p, v) -> p.toBuilder().timeFrameForUpwardLongAvg(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.aboveAvgRatingPricePerc, (p, v) -> p.toBuilder().aboveAvgRatingPricePerc(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.timeFrameForUpwardShortPrice, (p, v) -> p.toBuilder().timeFrameForUpwardShortPrice(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.timeFrameForOscillator, (p, v) -> p.toBuilder().timeFrameForOscillator(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.maxRSI, (p, v) -> p.toBuilder().maxRSI(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.minMarketCap, (p, v) -> p.toBuilder().minMarketCap(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.longMovingAvgTimes, (p, v) -> p.toBuilder().longMovingAvgTime(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.minRatesOfAvgInc, (p, v) -> p.toBuilder().minRateOfAvgInc(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.maxPERatios, (p, v) -> p.toBuilder().maxPERatio(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.minRatings, (p, v) -> p.toBuilder().minRating(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.maxRatings, (p, v) -> p.toBuilder().maxRating(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.maxMarketCap, (p, v) -> p.toBuilder().maxMarketCap(v).build());
            currentParams = findBestInParallel(allStocks, currentParams, config.riskFreeRate, (p, v) -> p.toBuilder().riskFreeRate(v).build());

            double newScore = evaluate(currentParams, allStocks);
            if (newScore > bestScore) {
                bestScore = newScore;
                improved = true;
            }
            logger.info("End of Iteration {}. Best Score: {}", iterations, bestScore);
        }

        return currentParams;
    }

    private <T> SimulationParams findBestInParallel(List<StockGraphState> allStocks, SimulationParams current, List<T> range, java.util.function.BiFunction<SimulationParams, T, SimulationParams> withFunc) {
        if (range == null || range.size() <= 1) return current;

        List<CompletableFuture<ParamScore>> futures = range.stream()
                .map(val -> CompletableFuture.supplyAsync(() -> {
                    SimulationParams test = withFunc.apply(current, val);
                    return new ParamScore(test, evaluate(test, allStocks));
                }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .max(Comparator.comparingDouble(ParamScore::score))
                        .map(ParamScore::params)
                        .orElse(current))
                .join();
    }

    private double evaluate(SimulationParams params, List<StockGraphState> stocks) {
        Simulation simulation = new Simulation(params);
        double totalScore = 0;
        int count = 0;

        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (StockGraphState stock : stocks) {
                        List<Double> movingAvg = StatsCalculator.MovingAvg(stock, params.longMovingAvgTime());
                        simulateWithDualScore(stock, startTime, searchTime, selectTime, movingAvg, simulation, timeFrame);
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        totalScore += simulation.calculateSimulationScore();
                        count++;
                    }
                }
            }
        }

        return count > 0 ? totalScore / count : -100.0;
    }

    private void simulateWithDualScore(StockGraphState stock, int daysToGoBack, int searchTime, int selectTime, List<Double> movingAvg, Simulation simulation, StocksTradeTimeFrame timeFrame) {
        int movingAvgSize = movingAvg.size();
        int timeStart = Math.max(0, movingAvgSize - daysToGoBack);
        int searchLimit = Math.min(timeStart + searchTime, movingAvgSize);

        for (int i = timeStart; i < searchLimit; i++) {
            SimulationResult res = simulation.calculateDualScore(stock.closePrices(), movingAvg, i, stock.stock(), stock.epss(), stock.rating(), stock.caps(), stock.volumes());

            if (res.heuristicScore() > 0.65) {
                double startingPriceOverAvg = stock.closePrices().get(i) / movingAvg.get(i);
                double cutOff = startingPriceOverAvg * simulation.params.sellCutOffPerc();

                for (int j = 1; j < searchLimit - i; j++) {
                    if (stock.closePrices().get(i + j) < (movingAvg.get(i + j) * cutOff)) {
                        double gain = (Math.max((movingAvg.get(i + j) * cutOff), stock.closePrices().get(i + j)) - stock.closePrices().get(i)) / stock.closePrices().get(i);
                        timeFrame.AddRow(new StockTrade(stock.stock().ticker_symbol(), gain, daysToGoBack - i + timeStart, j, startingPriceOverAvg, (double) stock.stock().market_cap_before_filing_date(), stock.dates().get(i)));

                        // Extract features for ML collection
                        double[] features = simulation.extractFeatures(stock.closePrices(), movingAvg, i, stock.stock(), stock.epss(), stock.rating(), stock.caps(), stock.volumes());
                        if (features != null) {
                            mlService.collectSample(new TrainingSample(features[0], features[1], features[2], features[3], features[4], features[5], features[6], gain));
                        }

                        i += j;
                        break;
                    }
                }
            }
        }
    }

    private record ParamScore(SimulationParams params, double score) {
    }
}
