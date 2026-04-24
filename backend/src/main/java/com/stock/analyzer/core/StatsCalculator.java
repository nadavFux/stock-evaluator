package com.stock.analyzer.core;

import com.stock.analyzer.model.SimulationRangeConfig;
import com.stock.analyzer.model.StockGraphState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class StatsCalculator {
    private static final Logger logger = LoggerFactory.getLogger(StatsCalculator.class);

    // Key Records for performance (Faster than string concatenation)
    public record MovingAvgKey(String ticker, int index, int timeFrame) {
    }

    public record VolatilityKey(String ticker, int index, int timeFrame) {
    }

    public record AvgVolumeKey(String ticker, int index, int timeFrame) {
    }

    public static final ConcurrentHashMap<String, Simulation> SIMULATIONS = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<MovingAvgKey, Double> preComputedMoving = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<VolatilityKey, Double> preComputedVolatility = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<AvgVolumeKey, Double> preComputedAvgVolume = new ConcurrentHashMap<>(100000);

    public static double calculateVolatility(List<Double> prices, int index, int timeFrame, String ticker) {
        if (prices == null || prices.isEmpty() || index < 0) return 0.0;
        VolatilityKey key = new VolatilityKey(ticker, index, timeFrame);
        Double cached = preComputedVolatility.get(key);
        if (cached != null) return cached;

        int start = Math.max(0, index - timeFrame + 1);
        int count = 0;
        double sum = 0.0;
        for (int i = start; i <= index; i++) {
            if (i < prices.size() && prices.get(i) != null) {
                sum += prices.get(i);
                count++;
            }
        }
        if (count == 0) return 0.0;
        double mean = sum / count;

        double varianceSum = 0.0;
        for (int i = start; i <= index; i++) {
            if (i < prices.size() && prices.get(i) != null) {
                varianceSum += Math.pow(prices.get(i) - mean, 2);
            }
        }

        double stdDev = Math.sqrt(varianceSum / count);
        preComputedVolatility.put(key, stdDev);
        return stdDev;
    }

    public static double calculateAvgVolume(List<Double> volumes, int index, int timeFrame, String ticker) {
        if (volumes == null || volumes.isEmpty() || index < 0) return 0.0;
        AvgVolumeKey key = new AvgVolumeKey(ticker, index, timeFrame);
        Double cached = preComputedAvgVolume.get(key);
        if (cached != null) return cached;

        int start = Math.max(0, index - timeFrame + 1);
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= index; i++) {
            if (i < volumes.size() && volumes.get(i) != null) {
                sum += volumes.get(i);
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0.0;
        preComputedAvgVolume.put(key, avg);
        return avg;
    }

    private static SimulationRangeConfig config;

    public static void init(SimulationRangeConfig config) {
        StatsCalculator.config = config;
        File outputDir = new File(config.outputPath);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
    }

    public static void AddSimulation(Simulation simulation) {
        SIMULATIONS.put(simulation.key, simulation);
    }

    public static double calculateSlidingAvg(List<Double> list, int endIndex, int timeFrame, String ticker) {
        if (list == null || list.isEmpty() || endIndex < 0 || timeFrame <= 0) return 0.0;
        MovingAvgKey key = new MovingAvgKey(ticker, endIndex, timeFrame);
        Double cached = preComputedMoving.get(key);
        if (cached != null) return cached;

        double sum = 0.0;
        int count = 0;
        int start = Math.max(0, endIndex - timeFrame + 1);
        for (int i = start; i <= endIndex; i++) {
            Double val = list.get(i);
            if (val != null) {
                sum += val;
                count++;
            }
        }
        double avg = count > 0 ? sum / count : 0.0;
        preComputedMoving.put(key, avg);
        return avg;
    }

    public static List<Double> MovingAvg(StockGraphState stock, int period) {
        List<Double> movingAverages = new ArrayList<>();
        List<Double> prices = stock.closePrices();
        for (int i = 0; i < prices.size(); i++) {
            if (i < period - 1) {
                movingAverages.add(null);
            } else {
                movingAverages.add(calculateSlidingAvg(prices, i, period, stock.stock().ticker_symbol()));
            }
        }
        return movingAverages;
    }

    public static double calculateRSI(List<Double> prices, int index, int period) {
        if (prices == null || index < period || index < 1) return 50.0;
        double gain = 0, loss = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double diff = prices.get(i) - prices.get(i - 1);
            if (diff >= 0) gain += diff; else loss -= diff;
        }
        double rs = (gain / period) / ((loss / period) + 0.00001);
        return 100 - (100 / (1 + rs));
    }

    public static double calculateATR(List<Double> high, List<Double> low, List<Double> close, int index, int period) {
        if (high == null || low == null || close == null || index < period || index < 1) return 0.0;
        double trSum = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double tr = Math.max(high.get(i) - low.get(i),
                        Math.max(Math.abs(high.get(i) - close.get(i-1)), Math.abs(low.get(i) - close.get(i-1))));
            trSum += tr;
        }
        return trSum / period;
    }

    public static double calculateMACD(List<Double> prices, int index) {
        if (prices == null || index < 26) return 0.0;
        double ema12 = calculateEMA(prices, index, 12);
        double ema26 = calculateEMA(prices, index, 26);
        return ema12 - ema26;
    }

    private static double calculateEMA(List<Double> prices, int index, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = prices.get(Math.max(0, index - period));
        for (int i = Math.max(0, index - period) + 1; i <= index; i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    public static double calculateBollingerB(List<Double> prices, int index, int period, String ticker) {
        if (prices == null || index < period) return 0.5;
        double ma = calculateSlidingAvg(prices, index, period, ticker);
        double stdDev = calculateVolatility(prices, index, period, ticker);
        if (stdDev == 0) return 0.5;
        return (prices.get(index) - (ma - 2 * stdDev)) / (4 * stdDev);
    }

    public static void clearAllCaches() {
        preComputedMoving.clear();
        preComputedVolatility.clear();
        preComputedAvgVolume.clear();
        SIMULATIONS.clear();
    }

    public static void clearSimulationCache() {
        SIMULATIONS.clear();
    }

    public static void WriteStat() {
        if (config == null) {
            logger.error("StatsCalculator not initialized with config");
            return;
        }
        String filePath = config.outputPath + File.separator + "data.csv";

        try (FileWriter writer = new FileWriter(filePath)) {
            var sortedSimulation = new ArrayList<>(SIMULATIONS.values()).stream()
                    .sorted(Comparator.comparingDouble(Simulation::calculateSimulationScore))
                    .toList();
            for (Simulation simulation : sortedSimulation) {
                writer.append(simulation.toString());
                writer.append("\n\n\n\n");
            }
            logger.info("CSV file created successfully at: {}", filePath);
        } catch (IOException e) {
            logger.error("An error occurred while writing the CSV file.", e);
        }
    }
}
