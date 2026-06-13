package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationResult;
import com.stock.analyzer.service.MLModelService;

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
    private double yearlyGain = 0;
    private long totalSimulationDays = 0;

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

    public void addSimulationDays(long days) {
        this.totalSimulationDays += days;
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
        // Apply 0.2% slippage penalty per trade
        gains[tradeCount] = gain - 0.003;
        holdDays[tradeCount] = days;
        tradeCount++;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public double getYearlyGain() {
        return yearlyGain;
    }

    /**
     * Evaluates a single day for a stock and returns results if heuristic threshold is met.
     */
    public SimulationResult evaluateStep(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        double heuristic = calculateHeuristic(pkg, stockIdx, dayIdx);
        if (heuristic <= params.buyThreshold()) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

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
     * Optimized O(1) heuristic calculation.
     * Rewritten to ensure 100% mathematical parity with TornadoVmOptimizer's unifiedKernel.
     */
    public double calculateHeuristic(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        if (shouldSkip(pkg, stockIdx, dayIdx)) return 0.0;

        double price = pkg.closePrices[stockIdx][dayIdx];
        double maVal = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (maVal <= 0.01) maVal = price;

        double maGap = price / (maVal + 1e-6);

        // Gap Score
        double maMax = params.higherPriceToLongAvgBuyIn();
        if (pkg.ratings[stockIdx][dayIdx] > 4.0) maMax *= params.aboveAvgRatingPricePerc();
        double vGap = (maGap - params.lowerPriceToLongAvgBuyIn()) / (maMax - params.lowerPriceToLongAvgBuyIn() + 1e-6);
        double scoreGap = (1.0 - Math.max(0.0, Math.min(1.0, vGap))) * (params.movingAvgGapWeight() / totalWeight);

        // Rating Score
        double ratingScore = normalize(pkg.ratings[stockIdx][dayIdx], params.minRating(), params.maxRating()) * (params.ratingWeight() / totalWeight);

        // RVol Score (Added 1e-6 denominator parity with GPU)
        double avgVol = pkg.avgVol30d[stockIdx][dayIdx] + 1e-6;
        double rvol = pkg.volumes[stockIdx][dayIdx] / avgVol;
        double scoreRVol = Math.max(0.0, Math.min(1.0, (rvol - 0.5) / 1.5)) * (params.rvolWeight() / totalWeight);

        // Momentum Score (Added 1e-6 denominator parity with GPU)
        double avgNow = pkg.getAvg(stockIdx, dayIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(stockIdx, Math.max(0, dayIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());
        double momentum = avgPrev > 0 ? avgNow / avgPrev : 1.0;
        double macdFactor = (pkg.macd[stockIdx][dayIdx] > 0) ? 1.2 : 0.8;
        double scoreMom = Math.max(0.0, Math.min(1.0, (momentum - params.minRateOfAvgInc()) / (params.minRateOfAvgInc() * 0.3 + 1e-6))) * macdFactor * (params.upwardIncRateWeight() / totalWeight);

        // Reversion Score (Fixed Reversion Fallacy: Only reward when price is BELOW moving average)
        double bbP = pkg.bbP[stockIdx][dayIdx];
        double bollingerFactor = (bbP < 0.2) ? (1.0 - bbP) : 0.5;
        double distFromMA = (maGap < 1.0) ? (1.0 - maGap) / 0.20 : 0.0;
        double scoreRev = Math.max(0.0, Math.min(1.0, distFromMA)) * bollingerFactor * (params.reversionToMeanWeight() / totalWeight);

        // PEG Score (Parity Fix: GPU defaults to 2.0 on missing data, yielding a score of 0. Old CPU defaulted to 1.0, yielding 0.5)
        double pegRatio = 2.0;
        int epsPrevIdx = dayIdx - 250;
        if (epsPrevIdx >= pkg.offsets[stockIdx]) {
            double epsCurr = pkg.epss[stockIdx][dayIdx];
            double epsHist = pkg.epss[stockIdx][epsPrevIdx];

            if (epsHist > 0.05) { // GPU threshold parity
                double epsGrowth = (epsCurr - epsHist) / epsHist;
                if (epsGrowth > 0.001 && epsCurr > 0.01) {
                    pegRatio = (price / epsCurr) / (epsGrowth * 100.0);
                }
            }
        }
        double scorePeg = (1.0 - Math.max(0.0, Math.min(1.0, pegRatio / 2.0))) * (params.pegWeight() / totalWeight);

        // Volatility Score (Parity Fix: GPU blends historical volatility and ATR. Old CPU ignored ATR.)
        double histVol = pkg.getVolatility(stockIdx, dayIdx, 20);
        double atrVol = pkg.atr[stockIdx][dayIdx] / (price + 1e-4);
        double blendVol = (histVol + atrVol) / 2.0;
        double scoreVol = (1.0 - Math.max(0.0, Math.min(1.0, blendVol / 0.05))) * (params.volatilityCompressionWeight() / totalWeight);

        return scoreGap + ratingScore + scoreRVol + scoreRev + scoreMom /*+ scorePeg*/ + scoreVol;
    }

    private boolean shouldSkip(SimulationDataPackage pkg, int sIdx, int dIdx) {
        if (dIdx - pkg.offsets[sIdx] < params.longMovingAvgTime() - 1) return true;

        double price = pkg.closePrices[sIdx][dIdx];
        // Parity Fix: GPU mActive requires price >= 0.05f
        if (price < 0.05) return true;

        double cap = pkg.caps[sIdx][dIdx];
        // Parity Fix: Matched GPU strict min/max constraints (removed cap > 0 fallback)
        if (cap < params.minMarketCap() || cap > params.maxMarketCap()) return true;
        //if (pkg.rsi[sIdx][dIdx] > params.maxRSI()) return true;

        int shortLookback = params.timeFrameForUpwardShortPrice();
        if (dIdx >= pkg.offsets[sIdx] + shortLookback) {
            return price < pkg.closePrices[sIdx][dIdx - shortLookback] * 0.8;
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

    public double calculateScore(long totalEvaluations) {
        this.yearlyGain = 0; // Ignore as requested
        if (tradeCount < 2) return -100.0;

        double sumExcess = 0.0;
        double sumSqExcess = 0.0;
        double totalHoldingDays = 0.0;

        double dailyRiskFreeRate = Math.pow(1.0 + params.riskFreeRate(), 1.0 / 252.0) - 1.0;

        for (int i = 0; i < tradeCount; i++) {
            double rawRet = gains[i];
            rawRet = Math.max(-1.0, Math.min(1.0, rawRet));
            double dur = Math.max(1.0, holdDays[i]);

            // 2. Orphaned Risk-Free Rate & 3. Geometric Compounding
            double tradeLogRet = Math.log(1.0 + rawRet);
            double excessLogRet = tradeLogRet - (dur * dailyRiskFreeRate);

            // 1. Duration Bias: Normalize by duration
            double dailyExcess = excessLogRet / dur;

            sumExcess += excessLogRet;
            sumSqExcess += dailyExcess * dailyExcess * dur;
            totalHoldingDays += dur;
        }

        if (totalHoldingDays < 2.0) return -100.0;

        double avgDailyExcess = sumExcess / totalHoldingDays;
        double variance = (sumSqExcess - (sumExcess * sumExcess / totalHoldingDays)) / (totalHoldingDays - 1.000001);
        double stdDev = Math.sqrt(Math.max(0.0, variance));

        // Annualized metrics
        double annualizedExcess = avgDailyExcess * 252.0;
        double annualizedStdDev = stdDev * Math.sqrt(252.0);

        // Standard Sharpe with 0.01 smoothing
        double sharpe = (annualizedExcess / (annualizedStdDev + 0.01));

        // Resolve Negative Sharpe Paradox: Penalize volatility when returns are negative
        if (annualizedExcess < 0) sharpe = annualizedExcess * (annualizedStdDev + 1.0);

        return sharpe;
    }

    public String getPerformanceReport(long totalEvaluations) {
        if (tradeCount == 0) return "No trades executed.";
        double sumLogRet = 0;
        long totalDays = 0;
        for (int i = 0; i < tradeCount; i++) {
            sumLogRet += Math.log(1.0 + Math.max(-1.0, Math.min(1.0, gains[i])));
            totalDays += holdDays[i];
        }
        // Geometric Average Return per trade (%)
        double avgGain = (Math.exp(sumLogRet / tradeCount) - 1.0) * 100.0;
        return String.format("Score: %.2f | Avg Gain: %.2f%% | Trades: %d | Avg Hold: %.1f days",
                calculateScore(totalEvaluations),
                avgGain,
                tradeCount, (double) totalDays / tradeCount);
    }

    @Override
    public String toString() {
        return "Params: " + key + " | Trades: " + tradeCount;
    }
}
