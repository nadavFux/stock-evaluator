package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
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
        SimulationDataPackage dataPkg = new SimulationDataPackage(allStocks);

        SimulationParams currentParams = new SimulationParams(
                config.sellCutOffPerc.get(0), config.lowerPriceToLongAvgBuyIn.get(0), config.higherPriceToLongAvgBuyIn.get(0),
                config.timeFrameForUpwardLongAvg.get(0), config.aboveAvgRatingPricePerc.get(0), config.timeFrameForUpwardShortPrice.get(0),
                config.timeFrameForOscillator.get(0), config.maxRSI.get(0), config.minMarketCap.get(0), config.longMovingAvgTimes.get(0),
                config.minRatesOfAvgInc.get(0), config.maxPERatios.get(0), config.minRatings.get(0), config.maxRatings.get(0), config.maxMarketCap.get(0),
                config.riskFreeRate.get(0)
        );
        double bestScore = evaluate(currentParams, dataPkg, false);
        logger.info("Initial Score: {}", bestScore);

        boolean improved = true;
        int iterations = 0;
        while (improved && iterations < 5) {
            improved = false;
            iterations++;
            logger.info("Starting Parallel Optimization Iteration {}", iterations);

            currentParams = findBestInParallel(dataPkg, currentParams, config.sellCutOffPerc, (p, v) -> p.toBuilder().sellCutOffPerc(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.lowerPriceToLongAvgBuyIn, (p, v) -> p.toBuilder().lowerPriceToLongAvgBuyIn(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.higherPriceToLongAvgBuyIn, (p, v) -> p.toBuilder().higherPriceToLongAvgBuyIn(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.timeFrameForUpwardLongAvg, (p, v) -> p.toBuilder().timeFrameForUpwardLongAvg(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.aboveAvgRatingPricePerc, (p, v) -> p.toBuilder().aboveAvgRatingPricePerc(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.timeFrameForUpwardShortPrice, (p, v) -> p.toBuilder().timeFrameForUpwardShortPrice(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.timeFrameForOscillator, (p, v) -> p.toBuilder().timeFrameForOscillator(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.maxRSI, (p, v) -> p.toBuilder().maxRSI(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.minMarketCap, (p, v) -> p.toBuilder().minMarketCap(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.longMovingAvgTimes, (p, v) -> p.toBuilder().longMovingAvgTime(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.minRatesOfAvgInc, (p, v) -> p.toBuilder().minRateOfAvgInc(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.maxPERatios, (p, v) -> p.toBuilder().maxPERatio(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.minRatings, (p, v) -> p.toBuilder().minRating(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.maxRatings, (p, v) -> p.toBuilder().maxRating(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.maxMarketCap, (p, v) -> p.toBuilder().maxMarketCap(v).build());
            currentParams = findBestInParallel(dataPkg, currentParams, config.riskFreeRate, (p, v) -> p.toBuilder().riskFreeRate(v).build());

            double newScore = evaluate(currentParams, dataPkg, false);
            if (newScore > bestScore) {
                bestScore = newScore;
                improved = true;
            }
            logger.info("End of Iteration {}. Best Score: {}", iterations, bestScore);
        }

        logger.info("Optimization complete. Final evaluation and ML data collection...");
        evaluate(currentParams, dataPkg, true);

        return currentParams;
    }

    private <T> SimulationParams findBestInParallel(SimulationDataPackage pkg, SimulationParams current, List<T> range, java.util.function.BiFunction<SimulationParams, T, SimulationParams> withFunc) {
        if (range == null || range.size() <= 1) return current;

        List<CompletableFuture<ParamScore>> futures = range.stream()
                .map(val -> CompletableFuture.supplyAsync(() -> {
                    SimulationParams test = withFunc.apply(current, val);
                    return new ParamScore(test, evaluate(test, pkg, false));
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

    private double evaluate(SimulationParams params, SimulationDataPackage pkg, boolean collectMLData) {
        Simulation simulation = new Simulation(params);
        double totalScore = 0;
        int count = 0;

        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, simulation, timeFrame, collectMLData);
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        totalScore += simulation.calculateSimulationScore();
                        count++;

                        // Pruning check
                        if (!collectMLData && count >= 5 && (totalScore / count < -20.0)) return -100.0;
                    }
                }
            }
        }

        return count > 0 ? totalScore / count : -100.0;
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

                for (int j = 1; j < searchLimit - i; j++) {
                    double currentPrice = pkg.closePrices[sIdx][i + j];
                    double currentMA = pkg.getAvg(sIdx, i + j, sim.params.longMovingAvgTime());
                    if (currentPrice < (currentMA * cutOff)) {
                        double exitPrice = Math.max(currentMA * cutOff, currentPrice);
                        double gain = (exitPrice - buyPrice) / buyPrice;
                        tf.AddRow(new StockTrade(pkg.tickers[sIdx], gain, daysBack - i + timeStart, j, buyPrice / buyMA, pkg.caps[sIdx][i], pkg.dates[sIdx][i]));

                        if (ml && res.features() != null) {
                            mlService.collectSample(new TrainingSample(res.features()[0], res.features()[1], res.features()[2], res.features()[3], res.features()[4], res.features()[5], res.features()[6], gain));
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
