package com.stock.analyzer.service;

import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;
import com.stock.analyzer.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
        if (rescueMode)
            logger.info("Rescue Mode Active: Optimization will prioritize finding trades over Sharpe Ratio.");

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

            com.stock.analyzer.core.StatsCalculator.clearSimulationCache();
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
                config.riskFreeRate.get(0),
                config.movingAvgGapWeight == null || config.movingAvgGapWeight.isEmpty() ? 0.2 : config.movingAvgGapWeight.get(0),
                config.reversionToMeanWeight == null || config.reversionToMeanWeight.isEmpty() ? 0.15 : config.reversionToMeanWeight.get(0),
                config.ratingWeight == null || config.ratingWeight.isEmpty() ? 0.2 : config.ratingWeight.get(0),
                config.upwardIncRateWeight == null || config.upwardIncRateWeight.isEmpty() ? 0.15 : config.upwardIncRateWeight.get(0),
                config.rvolWeight == null || config.rvolWeight.isEmpty() ? 0.1 : config.rvolWeight.get(0),
                config.pegWeight == null || config.pegWeight.isEmpty() ? 0.1 : config.pegWeight.get(0),
                config.volatilityCompressionWeight == null || config.volatilityCompressionWeight.isEmpty() ? 0.1 : config.volatilityCompressionWeight.get(0)
        );
    }

    private ParamScore runGeneration(SimulationParams center, double bestScoreSoFar, double radius, int popSize, boolean rescue, SimulationDataPackage pkg) {
        // --- STAGE 1: Discovery (All candidates on 33% of stocks) ---
        List<SimulationParams> candidates = new ArrayList<>();
        for (int i = 0; i < popSize; i++) {
            candidates.add(generateCandidate(center, radius, i, rescue, bestScoreSoFar));
        }

        List<Integer> subset33 = getShuffledIndices(pkg.stockCount).subList(0, Math.max(1, pkg.stockCount / 3));
        List<ParamScore> stage1Results = evaluateParallel(candidates, subset33, pkg, rescue);

        // --- STAGE 2: Refinement (Top 50 on 50% of stocks) ---
        List<SimulationParams> top50 = stage1Results.stream()
                .sorted(Comparator.comparingDouble(ParamScore::score).reversed())
                .limit(50)
                .map(ParamScore::params)
                .toList();

        List<Integer> subset50 = getShuffledIndices(pkg.stockCount).subList(0, Math.max(1, pkg.stockCount / 2));
        List<ParamScore> stage2Results = evaluateParallel(top50, subset50, pkg, rescue);

        // --- STAGE 3: Validation (Top 10 on 100% of stocks) ---
        List<SimulationParams> top10 = stage2Results.stream()
                .sorted(Comparator.comparingDouble(ParamScore::score).reversed())
                .limit(10)
                .map(ParamScore::params)
                .toList();

        List<Integer> allIndices = java.util.stream.IntStream.range(0, pkg.stockCount).boxed().toList();
        List<ParamScore> finalResults = evaluateParallel(top10, allIndices, pkg, false);

        return finalResults.stream()
                .max(Comparator.comparingDouble(ParamScore::score))
                .orElse(new ParamScore(center, bestScoreSoFar));
    }

    private List<ParamScore> evaluateParallel(List<SimulationParams> paramsList, List<Integer> stockSubset, SimulationDataPackage pkg, boolean rescue) {
        List<CompletableFuture<ParamScore>> futures = new ArrayList<>();
        for (SimulationParams p : paramsList) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                Simulation sim = new Simulation(p);
                int trades = evaluateRaw(p, pkg, sim, stockSubset);
                double score = rescue ? (-100.0 + trades) : (trades > Math.max(10, stockSubset.size() / 10) ? sim.calculateSimulationScore() : -100.0);
                return new ParamScore(p, score);
            }));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<Integer> getShuffledIndices(int count) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < count; i++) indices.add(i);
        Collections.shuffle(indices);
        return indices;
    }

    private SimulationParams generateCandidate(SimulationParams center, double radius, int idx, boolean rescue, double genBestScore) {
        if (idx == 0) return center;
        if (rescue && genBestScore == -100.0) return uniformRandom();
        return randomize(center, radius);
    }

    private int evaluateRaw(SimulationParams params, SimulationDataPackage pkg, Simulation simulation, List<Integer> stockSubset) {
        int tradeCount = 0;
        for (int startTime : config.startTimes) {
            for (int searchTime : config.searchTimes) {
                for (int selectTime : config.selectTimes) {
                    StocksTradeTimeFrame timeFrame = new StocksTradeTimeFrame(startTime, searchTime, selectTime);
                    for (int sIdx : stockSubset) {
                        fastSimulate(pkg, sIdx, startTime, searchTime, selectTime, simulation, timeFrame, false);
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
                        fastSimulate(pkg, sIdx, startTime, searchTime, selectTime, simulation, timeFrame, collectMLData);
                    }
                    if (!timeFrame.Trades().isEmpty()) {
                        simulation.AddTimeFrame(timeFrame);
                        tradeCount += timeFrame.Trades().size();
                    }
                }
            }
        }
        
        if (collectMLData && mlService.getSampleCount() < 100) {
            for (int sIdx = 0; sIdx < pkg.stockCount; sIdx++) {
                fastSimulate(pkg, sIdx, pkg.daysCount - 60, 60, 0, simulation, new StocksTradeTimeFrame(0, 0, 0), true);
            }
        }

        return tradeCount > pkg.stockCount / 10 ? simulation.calculateSimulationScore() : -100.0;
    }

    private void fastSimulate(SimulationDataPackage pkg, int sIdx, int daysBack, int searchTime, int selectTime, Simulation sim, StocksTradeTimeFrame tf, boolean ml) {
        int timeStart = Math.max(0, pkg.daysCount - daysBack);
        int searchLimit = Math.min(timeStart + searchTime, pkg.daysCount);
        
        // The absolute limit for a trade to finish is either the end of data 
        // OR timeStart + searchTime + selectTime (if selectTime > 0)
        int absoluteLimit = (selectTime > 0) ? Math.min(timeStart + searchTime + selectTime, pkg.daysCount) : pkg.daysCount;

        for (int i = timeStart; i < searchLimit; i++) {
            double heuristic = sim.getFastHeuristic(pkg, sIdx, i);
            if (heuristic > 0.65) {
                double buyPrice = pkg.closePrices[sIdx][i];
                double buyMA = pkg.getAvg(sIdx, i, sim.params.longMovingAvgTime());
                if (buyMA == 0) continue;
                double cutOff = (buyPrice / buyMA) * sim.params.sellCutOffPerc();

                // Collection for ML
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

                // Simulate trade hold period - now allowed to run until absoluteLimit
                for (int j = 1; j < absoluteLimit - i; j++) {
                    int currentIdx = i + j;
                    double currentPrice = pkg.closePrices[sIdx][currentIdx];
                    double currentMA = pkg.getAvg(sIdx, currentIdx, sim.params.longMovingAvgTime());
                    
                    // Exit at stop-loss OR at the end of the allowed period
                    if (currentPrice < (currentMA * cutOff) || (currentIdx == absoluteLimit - 1)) {
                        double gain = (currentPrice - buyPrice) / buyPrice;
                        tf.AddRow(new StockTrade(pkg.tickers[sIdx], gain, pkg.daysCount - i, j, buyPrice / buyMA, pkg.caps[sIdx][i], pkg.dates[sIdx][i]));
                        i = currentIdx; // Skip days where we were in the trade
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
                0.10,
                // Weights
                random.nextDouble(), random.nextDouble(), random.nextDouble(),
                random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble()
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
                0.10,
                // Weights
                clamp(center.movingAvgGapWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.reversionToMeanWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.ratingWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.upwardIncRateWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.rvolWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.pegWeight() + randomDouble(pctStep), 0.0, 1.0),
                clamp(center.volatilityCompressionWeight() + randomDouble(pctStep), 0.0, 1.0)
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
