package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationResult;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.ScoringWeights;
import com.stock.analyzer.service.MLModelService;

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
        double[] features = extractFeatures(price, movingAvg, index, stock, epss, rating, caps, volumes);
        if (features == null) return new SimulationResult(0.0, -1.0, 0.0, 0.0, 0.0, null);

        // 1. Calculate Heuristic Score (Traditional)
        double maScore = 1.0 - normalize(features[0], params.lowerPriceToLongAvgBuyIn(), params.higherPriceToLongAvgBuyIn());
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

        double currentPrice = pkg.closePrices[stockIdx][dayIdx];
        double currentMA = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (currentMA == 0) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double maGap = currentPrice / currentMA;
        double distFromMA = Math.abs(maGap - 1.0);
        double currentRating = pkg.ratings[stockIdx][dayIdx];
        
        double avgNow = pkg.getAvg(stockIdx, dayIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(stockIdx, Math.max(0, dayIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());
        double momentum = avgPrev > 0 ? avgNow / avgPrev : 1.0;

        double volatility = pkg.getVolatility(stockIdx, dayIdx, 20);

        double avgVol = 0.0;
        int volCount = 0;
        int volStart = Math.max(pkg.offsets[stockIdx], dayIdx - 30 + 1);
        for(int k = volStart; k <= dayIdx; k++) {
            avgVol += pkg.volumes[stockIdx][k];
            volCount++;
        }
        avgVol = volCount > 0 ? avgVol / volCount : 0.0;
        double rvol = avgVol > 0 ? pkg.volumes[stockIdx][dayIdx] / avgVol : 1.0;

        double peg = 1.0;
        int epsPrevIdx = dayIdx - 250;
        if (epsPrevIdx >= pkg.offsets[stockIdx] && pkg.epss[stockIdx][epsPrevIdx] > 0 && pkg.epss[stockIdx][dayIdx] > 0) {
            double epsGrowth = (pkg.epss[stockIdx][dayIdx] - pkg.epss[stockIdx][epsPrevIdx]) / pkg.epss[stockIdx][epsPrevIdx];
            double pe = currentPrice / pkg.epss[stockIdx][dayIdx];
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }

        // New Features for Fast Score
        double rsi = 50.0;
        if (dayIdx >= pkg.offsets[stockIdx] + 14) {
            double gain = 0, loss = 0;
            for (int k = dayIdx - 14 + 1; k <= dayIdx; k++) {
                double diff = pkg.closePrices[stockIdx][k] - pkg.closePrices[stockIdx][k-1];
                if (diff >= 0) gain += diff; else loss -= diff;
            }
            double rs = (gain / 14) / ((loss / 14) + 0.00001);
            rsi = 100 - (100 / (1 + rs));
        }

        double atrPerc = 0.02; // Default
        double macd = 0.0;
        double bbP = 0.5;
        double sectorRS = 1.0;

        double maScore = 1.0 - normalize(maGap, params.lowerPriceToLongAvgBuyIn(), params.higherPriceToLongAvgBuyIn());
        double reversionScore = normalize(distFromMA, 0.0, 0.20); 
        double ratingScore = normalize(currentRating, params.minRating(), params.maxRating());
        double momentumScore = normalize(momentum, params.minRateOfAvgInc(), params.minRateOfAvgInc() * 1.3);
        double rvolScore = normalize(rvol, 0.5, 2.0);
        double pegScore = 1.0 - normalize(peg, 0.0, 2.0);
        double volScore = 1.0 - normalize(volatility, 0.0, 0.05);

        double heuristic = (maScore * weights.movingAvgGapWeight()) +
               (reversionScore * weights.reversionToMeanWeight()) +
               (ratingScore * weights.ratingWeight()) +
               (momentumScore * weights.upwardIncRateWeight()) +
               (rvolScore * weights.rvolWeight()) +
               (pegScore * weights.pegWeight()) +
               (volScore * weights.volatilityCompressionWeight());

        double[] features = new double[]{maGap, distFromMA, currentRating, momentum, rvol, peg, volatility, rsi, atrPerc, macd, bbP, sectorRS};
        
        // 2. Calculate AI Prediction (if applicable during fast score)
        double aiPrediction = -1.0;
        // Optimization: only predict if heuristic is somewhat promising to save compute
        if (heuristic > 0.5 && mlService != null) {
             // In fastSimulate we only have point features, but for LSTM we need sequences.
             // This method will be updated to handle sequences if needed, but for now we'll 
             // skip AI prediction here unless we have the sequence.
        }

        return new SimulationResult(heuristic, aiPrediction, features[4], features[6], features[3], features);
    }

    public double[] extractFeatures(List<Double> price, List<Double> movingAvg, int index, Stock stock, List<Double> epss, List<Double> rating, List<Double> caps, List<Double> volumes) {
        if (price == null || movingAvg == null || epss == null || rating == null || caps == null || volumes == null) return null;
        if (index < 0 || index >= price.size() || index >= movingAvg.size() || index >= epss.size() || index >= rating.size() || index >= caps.size() || index >= volumes.size()) return null;
        
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

        // New Features
        double rsi = StatsCalculator.calculateRSI(price, index, 14);
        double atrPerc = StatsCalculator.calculateATR(price, price, price, index, 14) / currentPrice; // Approximation using close for high/low if not available
        double macd = StatsCalculator.calculateMACD(price, index);
        double bbP = StatsCalculator.calculateBollingerB(price, index, 20, stock.ticker_symbol());
        double sectorRS = 1.0; // Placeholder for now

        return new double[]{maGap, distFromMA, currentRating, momentum, rvol, peg, volatility, rsi, atrPerc, macd, bbP, sectorRS};
    }

    private double normalize(double val, double min, double max) {
        if (max == min) return 1.0;
        double n = (val - min) / (max - min);
        return Math.max(0.0, Math.min(1.0, n));
    }

    public double calculateSimulationScore() {
        double totalExcessReturn = 0.0;
        int totalTrades = 0;
        double dailyRiskFreeRate = Math.pow(1 + params.riskFreeRate(), 1.0 / 252) - 1;

        for (var tradeFrame : timeFrames.values()) {
            for (var trade : tradeFrame.Trades()) {
                double tradeReturn = trade.getLastGained() * 100;
                double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDays()) - 1) * 100;
                totalExcessReturn += (tradeReturn - riskFreeReturn);
                totalTrades++;
            }
        }
        return totalTrades > 0 ? totalExcessReturn / totalTrades : 0.0;
    }

    public void AddTimeFrame(StocksTradeTimeFrame timeFrame) {
        timeFrames.put(timeFrame.key, timeFrame);
    }

    public double getEval() {
        return calculateSimulationScore();
    }

    @Override
    public String toString() {
        return "Params: " + key + " | Score: " + calculateSimulationScore();
    }
}
