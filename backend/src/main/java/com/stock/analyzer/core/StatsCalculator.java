package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.model.StockGraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for calculating technical indicators.
 * Includes a caching layer to avoid redundant O(N) sliding window calculations.
 */
public class StatsCalculator {
    private static final Logger logger = LoggerFactory.getLogger(StatsCalculator.class);

    public record MovingAvgKey(String ticker, int index, int timeFrame) {}
    public record VolatilityKey(String ticker, int index, int timeFrame) {}
    public record AvgVolumeKey(String ticker, int index, int timeFrame) {}

    private static final ConcurrentHashMap<MovingAvgKey, Double> preComputedMoving = new ConcurrentHashMap<>(10000);
    private static final ConcurrentHashMap<VolatilityKey, Double> preComputedVolatility = new ConcurrentHashMap<>(10000);
    private static final ConcurrentHashMap<AvgVolumeKey, Double> preComputedAvgVolume = new ConcurrentHashMap<>(10000);

    private static SimulationRangeConfig config;

    public static void init(SimulationRangeConfig config) {
        StatsCalculator.config = config;
        File outputDir = new File(config.outputPath);
        if (!outputDir.exists()) outputDir.mkdirs();
    }

    public static double calculateVolatility(List<Double> prices, int index, int timeFrame, String ticker) {
        if (prices == null || prices.isEmpty() || index < 0) return 0.0;
        VolatilityKey key = new VolatilityKey(ticker, index, timeFrame);
        Double cached = preComputedVolatility.get(key);
        if (cached != null) return cached;

        int start = Math.max(0, index - timeFrame + 1);
        double sum = 0, count = 0;
        for (int i = start; i <= index; i++) {
            if (prices.get(i) != null) {
                sum += prices.get(i);
                count++;
            }
        }
        if (count == 0) return 0.0;
        double mean = sum / count;

        double varSum = 0;
        for (int i = start; i <= index; i++) {
            if (prices.get(i) != null) varSum += Math.pow(prices.get(i) - mean, 2);
        }

        double stdDev = Math.sqrt(varSum / count);
        preComputedVolatility.put(key, stdDev);
        return stdDev;
    }

    public static double calculateAvgVolume(List<Double> volumes, int index, int timeFrame, String ticker) {
        if (volumes == null || volumes.isEmpty() || index < 0) return 0.0;
        AvgVolumeKey key = new AvgVolumeKey(ticker, index, timeFrame);
        Double cached = preComputedAvgVolume.get(key);
        if (cached != null) return cached;

        int start = Math.max(0, index - timeFrame + 1);
        double sum = 0;
        int count = 0;
        for (int i = start; i <= index; i++) {
            if (volumes.get(i) != null) {
                sum += volumes.get(i);
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0.0;
        preComputedAvgVolume.put(key, avg);
        return avg;
    }

    public static double calculateSlidingAvg(List<Double> list, int endIndex, int timeFrame, String ticker) {
        if (list == null || list.isEmpty() || endIndex < 0 || timeFrame <= 0) return 0.0;
        MovingAvgKey key = new MovingAvgKey(ticker, endIndex, timeFrame);
        Double cached = preComputedMoving.get(key);
        if (cached != null) return cached;

        double sum = 0;
        int count = 0;
        int start = Math.max(0, endIndex - timeFrame + 1);
        for (int i = start; i <= endIndex; i++) {
            if (list.get(i) != null) {
                sum += list.get(i);
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0.0;
        preComputedMoving.put(key, avg);
        return avg;
    }

    public static List<Double> MovingAvg(StockGraphState stock, int period) {
        List<Double> result = new ArrayList<>();
        List<Double> prices = stock.closePrices();
        for (int i = 0; i < prices.size(); i++) {
            result.add(i < period - 1 ? null : calculateSlidingAvg(prices, i, period, stock.stock().ticker_symbol()));
        }
        return result;
    }

    public static double calculateRSI(List<Double> prices, int index, int period) {
        if (prices == null || index < period || index < 1) return 50.0;
        double gain = 0, loss = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff >= 0) gain += diff; else loss -= diff;
        }
        double rs = (gain / period) / ((loss / period) + 1e-6);
        return 100 - (100 / (1 + rs));
    }

    public static double calculateBollingerB(List<Double> prices, int index, int period, String ticker) {
        if (prices == null || index < period) return 0.5;
        double ma = calculateSlidingAvg(prices, index, period, ticker);
        double stdDev = calculateVolatility(prices, index, period, ticker);
        return stdDev == 0 ? 0.5 : (prices.get(index) - (ma - 2 * stdDev)) / (4 * stdDev);
    }

    public static void clearSimulationCache() {
        preComputedMoving.clear();
        preComputedVolatility.clear();
        preComputedAvgVolume.clear();
    }
    
    public static void WriteStat() {
        // No longer needed: Analysis report is now handled by broadcast results.
    }
    
    public static void AddSimulation(Simulation sim) {}
}
