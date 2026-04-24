package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationDataPackage;
import com.stock.analyzer.model.SimulationParams;
import com.stock.analyzer.model.SimulationResult;
import com.stock.analyzer.model.Stock;
import com.stock.analyzer.model.StockTrade;
import com.stock.analyzer.service.MLModelService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Simulation {
    public final HashMap<String, StocksTradeTimeFrame> timeFrames;
    public final SimulationParams params;
    public final String key;
    private MLModelService mlService;
    public boolean isTest = false;

    public Simulation(SimulationParams params) {
        this.params = params;
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

    public SimulationResult calculateParamScore(List<Double> price, List<Double> movingAvg, int index, Stock stock, List<Double> epss, List<Double> rating, List<Double> caps, List<Double> volumes) {
        double[] features = extractFeatures(price, movingAvg, index, stock, epss, rating, caps, volumes);
        if (features == null) return new SimulationResult(0.0, -1.0, 0.0, 0.0, 0.0, null);

        // 1. Calculate Heuristic Score
        double maMaxThreshold = params.higherPriceToLongAvgBuyIn();
        if (features[2] > 4.0) maMaxThreshold *= params.aboveAvgRatingPricePerc();

        double maScore = 1.0 - normalize(features[0], params.lowerPriceToLongAvgBuyIn(), maMaxThreshold);
        double reversionScore = normalize(features[1], 0.0, 0.20); 
        double ratingScore = normalize(features[2], params.minRating(), params.maxRating());
        double momentumScore = normalize(features[3], params.minRateOfAvgInc(), params.minRateOfAvgInc() * 1.3);
        double rvolScore = normalize(features[4], 0.5, 2.0);
        double pegScore = 1.0 - normalize(features[5], 0.0, 2.0);
        double volScore = 1.0 - normalize(features[6], 0.0, 0.05);

        double heuristic = (maScore * params.movingAvgGapWeight()) +
               (reversionScore * params.reversionToMeanWeight()) +
               (ratingScore * params.ratingWeight()) +
               (momentumScore * params.upwardIncRateWeight()) +
               (rvolScore * params.rvolWeight()) +
               (pegScore * params.pegWeight()) +
               (volScore * params.volatilityCompressionWeight());

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
        double heuristic = getFastHeuristic(pkg, stockIdx, dayIdx);
        if (heuristic == 0.0) return new SimulationResult(0.0, -1.0, 0, 0, 0, null);

        double[] features = extractFeaturesFast(pkg, stockIdx, dayIdx);
        return new SimulationResult(heuristic, -1.0, features[4], features[6], features[3], features);
    }

    public double getFastHeuristic(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        if (shouldSkipEvaluation(pkg, stockIdx, dayIdx)) return 0.0;

        double maVal = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (maVal == 0) {
            if (isTest) maVal = pkg.closePrices[stockIdx][dayIdx]; else return 0.0;
        }

        double maGap = pkg.closePrices[stockIdx][dayIdx] / maVal;
        double maMax = params.higherPriceToLongAvgBuyIn();
        if (pkg.ratings[stockIdx][dayIdx] > 4.0) maMax *= params.aboveAvgRatingPricePerc();
        
        double maScore = 1.0 - normalize(maGap, params.lowerPriceToLongAvgBuyIn(), maMax);
        double scoreSoFar = maScore * params.movingAvgGapWeight();
        double remainingWeight = 1.0 - params.movingAvgGapWeight();

        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double currentRating = pkg.ratings[stockIdx][dayIdx];
        double ratingScore = normalize(currentRating, params.minRating(), params.maxRating());
        scoreSoFar += ratingScore * params.ratingWeight();
        remainingWeight -= params.ratingWeight();
        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double rvol = (pkg.avgVol30d[stockIdx][dayIdx] > 0) ? pkg.volumes[stockIdx][dayIdx] / pkg.avgVol30d[stockIdx][dayIdx] : 1.0;
        double rvolScore = normalize(rvol, 0.5, 2.0);
        scoreSoFar += rvolScore * params.rvolWeight();
        remainingWeight -= params.rvolWeight();
        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double distFromMA = Math.abs(maGap - 1.0);
        double reversionScore = normalize(distFromMA, 0.0, 0.20);
        scoreSoFar += reversionScore * params.reversionToMeanWeight();
        remainingWeight -= params.reversionToMeanWeight();
        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double avgNow = pkg.getAvg(stockIdx, dayIdx, params.timeFrameForUpwardLongAvg());
        double avgPrev = pkg.getAvg(stockIdx, Math.max(0, dayIdx - params.timeFrameForUpwardLongAvg()), params.timeFrameForUpwardLongAvg());
        double momentum = avgPrev > 0 ? avgNow / avgPrev : 1.0;
        double momentumScore = normalize(momentum, params.minRateOfAvgInc(), params.minRateOfAvgInc() * 1.3);
        scoreSoFar += momentumScore * params.upwardIncRateWeight();
        remainingWeight -= params.upwardIncRateWeight();
        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double peg = 1.0;
        int epsPrevIdx = dayIdx - 250;
        if (epsPrevIdx >= pkg.offsets[stockIdx] && pkg.epss[stockIdx][epsPrevIdx] > 0 && pkg.epss[stockIdx][dayIdx] > 0) {
            double epsGrowth = (pkg.epss[stockIdx][dayIdx] - pkg.epss[stockIdx][epsPrevIdx]) / pkg.epss[stockIdx][epsPrevIdx];
            double pe = pkg.closePrices[stockIdx][dayIdx] / pkg.epss[stockIdx][dayIdx];
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }
        double pegScore = 1.0 - normalize(peg, 0.0, 2.0);
        scoreSoFar += pegScore * params.pegWeight();
        remainingWeight -= params.pegWeight();
        if (scoreSoFar + remainingWeight < 0.65) return 0.0;

        double volatility = pkg.getVolatility(stockIdx, dayIdx, 20);
        double volScore = 1.0 - normalize(volatility, 0.0, 0.05);
        scoreSoFar += volScore * params.volatilityCompressionWeight();

        return scoreSoFar;
    }

    private boolean shouldSkipEvaluation(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        if (isTest) return false;
        if (dayIdx - pkg.offsets[stockIdx] < params.longMovingAvgTime() - 1) return true;

        double cap = pkg.caps[stockIdx][dayIdx];
        if (cap > 0 && (cap < params.minMarketCap() || cap > params.maxMarketCap())) return true;

        if (pkg.rsi[stockIdx][dayIdx] > params.maxRSI()) return true;

        int shortLookback = params.timeFrameForUpwardShortPrice();
        if (dayIdx >= pkg.offsets[stockIdx] + shortLookback) {
             if (pkg.closePrices[stockIdx][dayIdx] < pkg.closePrices[stockIdx][dayIdx - shortLookback] * 0.8) return true;
        }

        return false;
    }

    public double[] extractFeaturesFast(SimulationDataPackage pkg, int stockIdx, int dayIdx) {
        double currentPrice = pkg.closePrices[stockIdx][dayIdx];
        double currentMA = pkg.getAvg(stockIdx, dayIdx, params.longMovingAvgTime());
        if (currentMA == 0) return new double[12];

        double maGap = currentPrice / currentMA;
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

        return new double[]{maGap, distFromMA, currentRating, momentum, rvol, peg, volatility, rsi, atrPerc, macd, bbP, sectorRS};
    }

    public double[] extractFeatures(List<Double> price, List<Double> movingAvg, int index, Stock stock, List<Double> epss, List<Double> rating, List<Double> caps, List<Double> volumes) {
        double currentMA = (index < movingAvg.size() && movingAvg.get(index) != null) ? movingAvg.get(index) : 0.0;
        if (currentMA == 0.0) return null;

        double currentPrice = price.get(index);
        double maGap = currentPrice / currentMA;
        double distFromMA = Math.abs(maGap - 1.0);
        double currentRating = (rating != null && index < rating.size()) ? rating.get(index) : 0.0;
        
        int prevIdx = Math.max(0, index - params.timeFrameForUpwardLongAvg());
        double avgPrevVal = (prevIdx < movingAvg.size() && movingAvg.get(prevIdx) != null) ? movingAvg.get(prevIdx) : 0.0;
        double momentum = avgPrevVal > 0 ? currentMA / avgPrevVal : 1.0;

        double avgVol = StatsCalculator.calculateAvgVolume(volumes, index, 30, stock.ticker_symbol());
        double rvol = (volumes != null && index < volumes.size() && avgVol > 0) ? volumes.get(index) / avgVol : 1.0;

        double peg = 1.0;
        if (epss != null && index >= 250 && epss.get(index - 250) > 0 && epss.get(index) > 0) {
            double epsGrowth = (epss.get(index) - epss.get(index - 250)) / epss.get(index - 250);
            double pe = currentPrice / epss.get(index);
            peg = epsGrowth > 0 ? pe / (epsGrowth * 100) : 2.0;
        }

        double volatility = StatsCalculator.calculateVolatility(price, index, 20, stock.ticker_symbol()) / (currentPrice + 0.0001);
        double rsi = StatsCalculator.calculateRSI(price, index, 14);
        double atrPerc = StatsCalculator.calculateATR(price, price, price, index, 14) / (currentPrice + 0.0001);
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

        int totalTrades = 0;
        for (var tradeFrame : timeFrames.values()) {
            for (var trade : tradeFrame.Trades()) {
                double tradeReturn = trade.getLastGained() * 100;
                double riskFreeReturn = (Math.pow(1 + dailyRiskFreeRate, trade.getDays()) - 1) * 100;
                excessReturns.add(tradeReturn - riskFreeReturn);
                totalTrades++;
            }
        }

        if (totalTrades == 0) return -100.0;

        double sum = 0.0;
        for (double r : excessReturns) sum += r;
        double avgExcess = sum / totalTrades;

        if (totalTrades < 2) return avgExcess - 5.0; 

        double varianceSum = 0.0;
        for (double r : excessReturns) varianceSum += Math.pow(r - avgExcess, 2);
        double stdDev = Math.sqrt(varianceSum / totalTrades);

        double sharpe = avgExcess / (stdDev + 0.0001);
        double volumeMultiplier = Math.min(1.0, Math.log10(totalTrades) / 3.0); 
        
        return sharpe * volumeMultiplier * 10.0; 
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
