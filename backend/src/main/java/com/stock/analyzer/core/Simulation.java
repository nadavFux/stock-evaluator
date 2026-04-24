package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationResult;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.ScoringWeights;
import com.stock.analyzer.service.MLModelService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class Simulation {
    public final HashMap<String, StocksTradeTimeFrame> timeFrames;
    public final SimulationParams params;
    public final ScoringWeights weights;
    public final String key;
    private MLModelService mlService;

    public Simulation(SimulationParams params) {
        this.params = params;
        this.weights = ScoringWeights.defaultWeights();
        this.timeFrames = new HashMap<>();
        this.key = GenerateKey(params);
    }

    public void setMLService(MLModelService service) {
        this.mlService = service;
    }

    public static String GenerateKey(SimulationParams p) {
        return p.sellCutOffPerc() + "," + p.lowerPriceToLongAvgBuyIn() + "," + p.higherPriceToLongAvgBuyIn() + "," + 
               p.timeFrameForUpwardLongAvg() + "," + p.aboveAvgRatingPricePerc() + "," + p.timeFrameForUpwardShortPrice() + "," + 
               p.timeFrameForOscillator() + "," + p.maxRSI() + "," + p.minMarketCap() + "," + p.longMovingAvgTime() + "," + 
               p.minRateOfAvgInc() + "," + p.maxPERatio() + "," + p.minRating() + "," + p.maxRating() + "," + p.maxMarketCap() + "," + p.riskFreeRate();
    }

    public SimulationResult calculateDualScore(List<Double> price, List<Double> movingAvg, int index, Stock stock, List<Double> epss, List<Double> rating, List<Double> caps, List<Double> volumes) {
        double currentPrice = price.get(index);
        double currentCap = (caps != null && index < caps.size()) ? caps.get(index) : 0.0;
        
        // 0. Strict Filters
        if (currentCap > 0 && (currentCap < params.minMarketCap() || currentCap > params.maxMarketCap())) return new SimulationResult(0.0, -1.0, 0.0, 0.0, 0.0, null);
        
        double rsi = StatsCalculator.calculateRSI(price, index, 14);
        if (rsi > params.maxRSI()) return new SimulationResult(0.0, -1.0, 0.0, 0.0, 0.0, null);

        if (index >= 1 && price.get(index) < price.get(index - 1)) {
            // Very basic short-term momentum check
        }

        double[] features = extractFeatures(price, movingAvg, index, stock, epss, rating, caps, volumes);
        if (features == null) return new SimulationResult(0.0, -1.0, 0.0, 0.0, 0.0, null);

        // 1. Calculate Heuristic Score (Traditional)
        double maMaxThreshold = params.higherPriceToLongAvgBuyIn();
        if (features[2] > 4.0) maMaxThreshold *= params.aboveAvgRatingPricePerc();

        double maScore = 1.0 - normalize(features[0], params.lowerPriceToLongAvgBuyIn(), maMaxThreshold);
        double reversionScore = normalize(features[1], 0.0, 0.20); 
        double ratingScore = normalize(features[2], params.minRating(), params.maxRating());
        double momentumScore = normalize(features[3], params.minRateOfAvgInc(), params.minRateOfAvgInc() * 1.3);
        double rvolScore = normalize(features[4], 0.5, 2.0);
        double pegScore = 1.0 - normalize(features[5], 0.0, 2.0);
        double volScore = 1.0 - normalize(features[6], 0.0, 0.05);

        double heuristic = (maScore * weights.movingAvgGapWeight()) +
               (reversionScore * weights.reversionToMeanWeight()) +
               (ratingScore * weights.ratingWeight()) +
               (momentumScore * weights.upwardIncRateWeight()) +
               (rvolScore * weights.rvolWeight()) +
               (pegScore * weights.pegWeight()) +
               (volScore * weights.volatilityCompressionWeight());

        // 2. Calculate AI Prediction
        double aiPrediction = -1.0;
        double q05 = 0.0, q50 = 0.0, q95 = 0.0;
        if (mlService != null && index >= 30) {
            float[][] sequence = new float[30][12];
            for (int k = 0; k < 30; k++) {
                double[] stepFeatures = extractFeatures(price, movingAvg, index - 29 + k, stock, epss, rating, caps, volumes);
                if (stepFeatures != null) {
                    for (int f = 0; f < 12; f++) sequence[k][f] = (float) stepFeatures[f];
                }
            }
            double[] predictions = mlService.predict(sequence);
            q05 = predictions[0];
            q50 = predictions[1];
            q95 = predictions[2];
            aiPrediction = q50;
        }

        return new SimulationResult(heuristic, aiPrediction, features[4], features[6], features[3], features, q05, q50, q95);
    }

    public SimulationResult calculateFastScore(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        // Enforce strict lookback window to match StatsCalculator behavior
        if (dayIdx - pkg.offsets[stockIdx] < params.longMovingAvgTime() - 1) {
            return new SimulationResult(0.0, -1.0, 0, 0, 0, null);
        }

        // 0. Strict Filters (Missing Params Integration)
        double cap = pkg.caps[stockIdx][dayIdx];
        if (cap > 0 && (cap < params.minMarketCap() || cap > params.maxMarketCap())) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double rsiVal = pkg.rsi[stockIdx][dayIdx];
        if (rsiVal > params.maxRSI()) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        int shortLookback = params.timeFrameForUpwardShortPrice();
        if (dayIdx >= pkg.offsets[stockIdx] + shortLookback) {
             if (pkg.closePrices[stockIdx][dayIdx] < pkg.closePrices[stockIdx][dayIdx - shortLookback]) {
                 return new SimulationResult(0.0, -1.0, 0, 0, 0, null); // Downtrending short-term
             }
        }

        double currentPrice = pkg.closePrices[stockIdx][dayIdx];
        double currentMA = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (currentMA == 0) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double maGap = currentPrice / currentMA;
        
        // 1. maScore calculation (Inlined normalize)
        double maMin = params.lowerPriceToLongAvgBuyIn();
        double maMax = params.higherPriceToLongAvgBuyIn();
        if (pkg.ratings[stockIdx][dayIdx] > 4.0) maMax *= params.aboveAvgRatingPricePerc();

        double maScore = 1.0 - (maMax == maMin ? 1.0 : Math.max(0.0, Math.min(1.0, (maGap - maMin) / (maMax - maMin))));
        
        // --- PRUNING START ---
        double currentScoreSoFar = maScore * weights.movingAvgGapWeight();
        double remainingWeight = weights.reversionToMeanWeight() + weights.ratingWeight() + weights.upwardIncRateWeight() + 
                                 weights.rvolWeight() + weights.pegWeight() + weights.volatilityCompressionWeight();
        
        // Lowered threshold to 0.60 to allow for safety buffer
        if (currentScoreSoFar + remainingWeight < 0.60) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);
        // --- PRUNING END ---

        double distFromMA = Math.abs(maGap - 1.0);
        double currentRating = pkg.ratings[stockIdx][dayIdx];
        
        double avgNow = pkg.getAvg(stockIdx, dayIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(stockIdx, Math.max(0, dayIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());
        double momentum = avgPrev > 0 ? avgNow / avgPrev : 1.0;

        double volatility = pkg.getVolatility(stockIdx, dayIdx, 20);
        double rvol = pkg.avgVol30d[stockIdx][dayIdx] > 0 ? pkg.volumes[stockIdx][dayIdx] / pkg.avgVol30d[stockIdx][dayIdx] : 1.0;

        double peg = 1.0;
        int epsPrevIdx = dayIdx - 250;
        if (epsPrevIdx >= pkg.offsets[stockIdx] && pkg.epss[stockIdx][epsPrevIdx] > 0 && pkg.epss[stockIdx][dayIdx] > 0) {
            double epsGrowth = (pkg.epss[stockIdx][dayIdx] - pkg.epss[stockIdx][epsPrevIdx]) / pkg.epss[stockIdx][epsPrevIdx];
            double pe = currentPrice / pkg.epss[stockIdx][dayIdx];
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }

        double rsi = pkg.rsi[stockIdx][dayIdx];
        double atrPerc = pkg.atr[stockIdx][dayIdx] / currentPrice;
        double macd = pkg.macd[stockIdx][dayIdx];
        double bbP = pkg.bbP[stockIdx][dayIdx];
        double sectorRS = 1.0;

        // Inlined heuristics
        double reversionScore = Math.max(0.0, Math.min(1.0, (distFromMA - 0.0) / (0.20 - 0.0)));
        currentScoreSoFar += reversionScore * weights.reversionToMeanWeight();
        remainingWeight -= weights.reversionToMeanWeight();
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double ratingScore = Math.max(0.0, Math.min(1.0, (currentRating - params.minRating()) / (params.maxRating() - params.minRating())));
        currentScoreSoFar += ratingScore * weights.ratingWeight();
        remainingWeight -= weights.ratingWeight();
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double momMin = params.minRateOfAvgInc();
        double momMax = momMin * 1.3;
        double momentumScore = Math.max(0.0, Math.min(1.0, (momentum - momMin) / (momMax - momMin)));
        currentScoreSoFar += momentumScore * weights.upwardIncRateWeight();
        remainingWeight -= weights.upwardIncRateWeight();
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double rvolScore = Math.max(0.0, Math.min(1.0, (rvol - 0.5) / (2.0 - 0.5)));
        currentScoreSoFar += rvolScore * weights.rvolWeight();
        remainingWeight -= weights.rvolWeight();
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double pegScore = 1.0 - Math.max(0.0, Math.min(1.0, (peg - 0.0) / (2.0 - 0.0)));
        currentScoreSoFar += pegScore * weights.pegWeight();
        remainingWeight -= weights.pegWeight();
        if (currentScoreSoFar + remainingWeight < 0.65) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double volScore = 1.0 - Math.max(0.0, Math.min(1.0, (volatility - 0.0) / (0.05 - 0.0)));
        double heuristic = currentScoreSoFar + (volScore * weights.volatilityCompressionWeight());

        double[] features = new double[]{maGap, distFromMA, currentRating, momentum, rvol, peg, volatility, rsi, atrPerc, macd, bbP, sectorRS};
        return new SimulationResult(heuristic, -1.0, features[4], features[6], features[3], features);
    }

    public double[] extractFeatures(List<Double> price, List<Double> movingAvg, int index, Stock stock, List<Double> epss, List<Double> rating, List<Double> caps, List<Double> volumes) {
        // This method is now legacy or used only for direct calls. 
        // For performance, we prefer SimulationDataPackage based calls.
        // We'll keep it functional but it still uses StatsCalculator if no SimulationDataPackage is available.
        // Note: In ParamOptimizer, we've replaced logic to use pkg features where possible.
        
        Double currentMA = movingAvg.get(index);
        if (currentMA == null || currentMA == 0.0) return null;

        double currentPrice = price.get(index);
        double maGap = currentPrice / currentMA;
        double distFromMA = Math.abs(maGap - 1.0);
        double currentRating = rating.get(index);
        
        Double avgNowVal = movingAvg.get(index);
        Double avgPrevVal = movingAvg.get(Math.max(0, index - params.timeFrameForUpwardLongAvg()));
        double avgNow = (avgNowVal != null) ? avgNowVal : 0.0;
        double avgPrev = (avgPrevVal != null) ? avgPrevVal : 0.0;
        double momentum = (avgPrev > 0) ? avgNow / avgPrev : 1.0;

        double avgVol = StatsCalculator.calculateAvgVolume(volumes, index, 30, stock.ticker_symbol());
        double rvol = avgVol > 0 ? volumes.get(index) / avgVol : 1.0;

        double peg = 1.0;
        if (index >= 250 && epss.get(index - 250) > 0 && epss.get(index) > 0) {
            double epsGrowth = (epss.get(index) - epss.get(index - 250)) / epss.get(index - 250);
            double pe = currentPrice / epss.get(index);
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }

        double volatility = StatsCalculator.calculateVolatility(price, index, 20, stock.ticker_symbol()) / (currentPrice + 0.0001);
        double rsi = StatsCalculator.calculateRSI(price, index, 14);
        double atrPerc = StatsCalculator.calculateATR(price, price, price, index, 14) / currentPrice;
        double macd = StatsCalculator.calculateMACD(price, index);
        double bbP = StatsCalculator.calculateBollingerB(price, index, 20, stock.ticker_symbol());
        double sectorRS = 1.0;

        return new double[]{maGap, distFromMA, currentRating, momentum, rvol, peg, volatility, rsi, atrPerc, macd, bbP, sectorRS};
    }

    private double normalize(double val, double min, double max) {
        if (max == min) return 1.0;
        double n = (val - min) / (max - min);
        return Math.max(0.0, Math.min(1.0, n));
    }

    public double calculateSimulationScore() {
        List<Double> excessReturns = new ArrayList<>();
        double dailyRiskFreeRate = Math.pow(1 + params.riskFreeRate(), 1.0 / 252) - 1;

        for (var tradeFrame : timeFrames.values()) {
            for (var trade : tradeFrame.Trades()) {
                double tradeReturn = trade.getLastGained() * 100;
                double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDays()) - 1) * 100;
                excessReturns.add(tradeReturn - riskFreeReturn);
            }
        }

        if (excessReturns.isEmpty()) return 0.0;

        double sum = 0.0;
        for (double r : excessReturns) sum += r;
        double avgExcess = sum / excessReturns.size();

        if (excessReturns.size() < 2) return avgExcess;

        // Calculate Standard Deviation to penalize high variance (risk)
        double varianceSum = 0.0;
        for (double r : excessReturns) varianceSum += Math.pow(r - avgExcess, 2);
        double stdDev = Math.sqrt(varianceSum / excessReturns.size());

        // Risk-Adjusted Score: Penalize volatility of returns
        // We subtract 0.5 * StdDev from the average. This discourages one-hit wonders.
        return avgExcess - (stdDev * 0.5);
    }

    public String getPerformanceReport() {
        double totalExcessReturn = 0.0;
        double totalRawReturn = 0.0;
        double totalRiskFreeReturn = 0.0;
        int totalTrades = 0;
        double dailyRiskFreeRate = Math.pow(1 + params.riskFreeRate(), 1.0 / 252) - 1;

        for (var tradeFrame : timeFrames.values()) {
            for (var trade : tradeFrame.Trades()) {
                double tradeReturn = trade.getLastGained() * 100;
                double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDays()) - 1) * 100;
                totalRawReturn += tradeReturn;
                totalRiskFreeReturn += riskFreeReturn;
                totalExcessReturn += (tradeReturn - riskFreeReturn);
                totalTrades++;
            }
        }
        
        if (totalTrades == 0) return "No trades executed.";
        double avgExcess = totalExcessReturn / totalTrades;
        double avgRaw = totalRawReturn / totalTrades;
        double avgRF = totalRiskFreeReturn / totalTrades;
        
        return String.format("Score (Avg Excess Return): %.2f%% | Avg Raw Return: %.2f%% | Avg Risk-Free Return: %.2f%% | Total Trades: %d", 
                avgExcess, avgRaw, avgRF, totalTrades);
    }

    public void AddTimeFrame(StocksTradeTimeFrame timeFrame) {
        timeFrames.put(timeFrame.key, timeFrame);
    }

    public double getEval() {
        return calculateSimulationScore();
    }

    @Override
    public String toString() {
        return "Params: " + key + " | " + getPerformanceReport();
    }
}
