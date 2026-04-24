package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationResult;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.service.MLModelService;

import java.util.ArrayList;
import java.util.List;

/**
 * Core simulation engine responsible for scoring stock windows using a hybrid
 * heuristic + AI approach. Optimized for high-throughput parameter optimization.
 */
public class Simulation {
    public final SimulationParams params;
    public final String key;
    private MLModelService mlService;

    // Weight Normalization for early-exit precision
    private final double totalWeight;

    // High-performance primitive trade tracking (Zero object allocation during optimization)
    private double[] gains = new double[1024];
    private int[] holdDays = new int[1024];
    private int tradeCount = 0;

    // Pre-allocated sequence buffer for AI inference to eliminate GC pressure
    private final float[][] sequenceBuffer = new float[30][12];

    public Simulation(SimulationParams params) {
        this.params = params;
        this.key = GenerateKey(params);

        // Pre-calculate total weight for dynamic normalization
        this.totalWeight = params.movingAvgGapWeight() + params.reversionToMeanWeight() +
                params.ratingWeight() + params.upwardIncRateWeight() +
                params.rvolWeight() + params.pegWeight() + params.volatilityCompressionWeight();
    }

    public void setMLService(MLModelService service) {
        this.mlService = service;
    }

    public static String GenerateKey(SimulationParams p) {
        return String.format("%s,%s,%s,%d,%s,%d,%d,%s,%s,%d,%s,%d,%s,%s,%s,%s,%s",
                p.sellCutOffPerc(), p.lowerPriceToLongAvgBuyIn(), p.higherPriceToLongAvgBuyIn(),
                p.timeFrameForUpwardLongAvg(), p.aboveAvgRatingPricePerc(), p.timeFrameForUpwardShortPrice(),
                p.timeFrameForOscillator(), p.maxRSI(), p.minMarketCap(), p.longMovingAvgTime(),
                p.minRateOfAvgInc(), p.maxPERatio(), p.minRating(), p.maxRating(), p.maxMarketCap(), 
                p.riskFreeRate(), p.buyThreshold());
    }

    /**
     * Records a simulated trade for scoring.
     */
    public void recordTrade(double gain, int days) {
        if (tradeCount >= gains.length) {
            gains = java.util.Arrays.copyOf(gains, gains.length * 2);
            holdDays = java.util.Arrays.copyOf(holdDays, holdDays.length * 2);
        }
        gains[tradeCount] = gain;
        holdDays[tradeCount] = days;
        tradeCount++;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    /**
     * Evaluates a single day for a stock and returns results if heuristic threshold is met.
     */
    public SimulationResult evaluateStep(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        double heuristic = calculateHeuristic(pkg, stockIdx, dayIdx);
        if (heuristic == 0.0) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double[] features = extractFeatures(pkg, stockIdx, dayIdx);
        
        // AI Inference if model is active
        double q50 = -1.0;
        if (mlService != null && dayIdx >= pkg.offsets[stockIdx] + 29) {
            for (int k = 0; k < 30; k++) {
                double[] stepFeatures = extractFeatures(pkg, stockIdx, dayIdx - 29 + k);
                for (int f = 0; f < 12; f++) sequenceBuffer[k][f] = (float) stepFeatures[f];
            }
            double[] preds = mlService.predict(sequenceBuffer);
            q50 = preds[1];
        }

        return new SimulationResult(heuristic, q50, features[4], features[6], features[3], features);
    }

    /**
     * Optimized O(1) heuristic calculation using pre-computed indicators.
     */
    public double calculateHeuristic(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        if (shouldSkip(pkg, stockIdx, dayIdx)) return 0.0;

        double maVal = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (maVal <= 0) return 0.0;

        double maGap = pkg.closePrices[stockIdx][dayIdx] / maVal;
        double maMax = params.higherPriceToLongAvgBuyIn();
        if (pkg.ratings[stockIdx][dayIdx] > 4.0) maMax *= params.aboveAvgRatingPricePerc();

        // Layered evaluation with dynamic weight normalization for early exit performance
        double maScore = (1.0 - normalize(maGap, params.lowerPriceToLongAvgBuyIn(), maMax)) * (params.movingAvgGapWeight() / totalWeight);
        double remainingWeight = (totalWeight - params.movingAvgGapWeight()) / totalWeight;
        double scoreSoFar = maScore;
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double ratingScore = normalize(pkg.ratings[stockIdx][dayIdx], params.minRating(), params.maxRating()) * (params.ratingWeight() / totalWeight);
        scoreSoFar += ratingScore;
        remainingWeight -= (params.ratingWeight() / totalWeight);
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double rvol = (pkg.avgVol30d[stockIdx][dayIdx] > 0) ? pkg.volumes[stockIdx][dayIdx] / pkg.avgVol30d[stockIdx][dayIdx] : 1.0;
        scoreSoFar += normalize(rvol, 0.5, 2.0) * (params.rvolWeight() / totalWeight);
        remainingWeight -= (params.rvolWeight() / totalWeight);
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double distFromMA = Math.abs(maGap - 1.0);
        scoreSoFar += normalize(distFromMA, 0.0, 0.20) * (params.reversionToMeanWeight() / totalWeight);
        remainingWeight -= (params.reversionToMeanWeight() / totalWeight);
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double avgNow = pkg.getAvg(stockIdx, dayIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(stockIdx, Math.max(0, dayIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());
        double momentum = avgPrev > 0 ? avgNow / avgPrev : 1.0;
        scoreSoFar += normalize(momentum, params.minRateOfAvgInc(), params.minRateOfAvgInc() * 1.3) * (params.upwardIncRateWeight() / totalWeight);
        remainingWeight -= (params.upwardIncRateWeight() / totalWeight);
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double peg = 1.0;
        int epsPrevIdx = dayIdx - 250;
        if (epsPrevIdx >= pkg.offsets[stockIdx] && pkg.epss[stockIdx][epsPrevIdx] > 0 && pkg.epss[stockIdx][dayIdx] > 0) {
            double epsGrowth = (pkg.epss[stockIdx][dayIdx] - pkg.epss[stockIdx][epsPrevIdx]) / pkg.epss[stockIdx][epsPrevIdx];
            double pe = pkg.closePrices[stockIdx][dayIdx] / pkg.epss[stockIdx][dayIdx];
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }
        scoreSoFar += (1.0 - normalize(peg, 0.0, 2.0)) * (params.pegWeight() / totalWeight);
        remainingWeight -= (params.pegWeight() / totalWeight);
        if (scoreSoFar + remainingWeight < params.buyThreshold()) return 0.0;

        double volatility = pkg.getVolatility(stockIdx, dayIdx, 20);
        scoreSoFar += (1.0 - normalize(volatility, 0.0, 0.05)) * (params.volatilityCompressionWeight() / totalWeight);

        return scoreSoFar;
    }

    private boolean shouldSkip(SimulationDataPackage pkg, int sIdx, int dIdx) {
        if (dIdx - pkg.offsets[sIdx] < params.longMovingAvgTime() - 1) return true;
        double cap = pkg.caps[sIdx][dIdx];
        if (cap > 0 && (cap < params.minMarketCap() || cap > params.maxMarketCap())) return true;
        if (pkg.rsi[sIdx][dIdx] > params.maxRSI()) return true;

        int shortLookback = params.timeFrameForUpwardShortPrice();
        if (dIdx >= pkg.offsets[sIdx] + shortLookback) {
            return pkg.closePrices[sIdx][dIdx] < pkg.closePrices[sIdx][dIdx - shortLookback] * 0.8;
        }
        return false;
    }

    public double[] extractFeatures(SimulationDataPackage pkg, int sIdx, int dIdx) {
        double price = pkg.closePrices[sIdx][dIdx];
        double ma = pkg.getAvg(sIdx, dIdx, params.longMovingAvgTime());
        if (ma == 0) return new double[12];

        double avgNow = pkg.getAvg(sIdx, dIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(sIdx, Math.max(0, dIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());

        return new double[]{
            price / ma, Math.abs((price / ma) - 1.0), pkg.ratings[sIdx][dIdx], 
            avgPrev > 0 ? avgNow / avgPrev : 1.0,
            pkg.avgVol30d[sIdx][dIdx] > 0 ? pkg.volumes[sIdx][dIdx] / pkg.avgVol30d[sIdx][dIdx] : 1.0,
            0.0, // PEG Placeholder (Calculated in heuristic but complex for feature array)
            pkg.getVolatility(sIdx, dIdx, 20),
            pkg.rsi[sIdx][dIdx],
            pkg.atr[sIdx][dIdx] / price,
            pkg.macd[sIdx][dIdx],
            pkg.bbP[sIdx][dIdx],
            1.0 // Sector RS placeholder
        };
    }

    private double normalize(double val, double min, double max) {
        if (max <= min) return 1.0;
        return Math.max(0.0, Math.min(1.0, (val - min) / (max - min)));
    }

    /**
     * Calculates the final risk-adjusted score using trade density and duration multipliers.
     */
    public double calculateScore(int totalFrames) {
        if (tradeCount < 2) return -100.0;

        double dailyRiskFreeRate = Math.pow(1 + params.riskFreeRate(), 1.0 / 252) - 1;
        List<Double> dailyExcessReturns = new ArrayList<>();
        long totalTradeDays = 0;

        for (int i = 0; i < tradeCount; i++) {
            double tradeReturn = gains[i] * 100;
            int days = Math.max(1, holdDays[i]);
            double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, days) - 1) * 100;
            dailyExcessReturns.add((tradeReturn - riskFreeReturn) / days);
            totalTradeDays += days;
        }

        double sum = 0.0;
        for (double r : dailyExcessReturns) sum += r;
        double avgDailyExcess = sum / tradeCount;

        double varianceSum = 0.0;
        for (double r : dailyExcessReturns) varianceSum += Math.pow(r - avgDailyExcess, 2);
        double stdDevDaily = Math.sqrt(varianceSum / (tradeCount - 1));
        double sharpe = (avgDailyExcess / (stdDevDaily + 0.01)) * Math.sqrt(252);

        // Multipliers for statistical reliability
        double absoluteFactor = Math.min(1.0, (double) tradeCount / 40.0);
        double densityFactor = Math.min(1.0, ((double) tradeCount / Math.max(1, totalFrames)) / 10.0);
        double volumeMultiplier = Math.sqrt(absoluteFactor * densityFactor);
        double durationMultiplier = Math.min(1.0, ((double) totalTradeDays / tradeCount) / 5.0);

        return sharpe * volumeMultiplier * durationMultiplier * 10.0;
    }

    public String getPerformanceReport(int totalFrames) {
        if (tradeCount == 0) return "No trades executed.";
        double totalGains = 0;
        long totalDays = 0;
        for (int i = 0; i < tradeCount; i++) {
            totalGains += gains[i];
            totalDays += holdDays[i];
        }
        return String.format("Score: %.2f | Avg Gain: %.2f%% | Trades: %d | Avg Hold: %.1f days",
                calculateScore(totalFrames), 
                (totalGains / tradeCount) * 100,
                tradeCount, (double) totalDays / tradeCount);
    }

    @Override
    public String toString() {
        return "Params: " + key + " | Trades: " + tradeCount;
    }
}
