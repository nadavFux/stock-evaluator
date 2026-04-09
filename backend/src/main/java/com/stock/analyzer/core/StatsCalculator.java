package com.stock.analyzer.core;

import com.stock.analyzer.model.StockGraphState;
import com.stock.analyzer.model.StockTrade;
import com.stock.analyzer.model.SimulationRangeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class StatsCalculator {
    private static final Logger logger = LoggerFactory.getLogger(StatsCalculator.class);
    
    // Key Records for performance (Faster than string concatenation)
    public record RangeKey(String ticker, int start, int end) {}
    public record MovingAvgKey(String ticker, int index, int timeFrame) {}

    public record VolatilityKey(String ticker, int index, int timeFrame) {}
    public record AvgVolumeKey(String ticker, int index, int timeFrame) {}

    public static final ConcurrentHashMap<String, Simulation> SIMULATIONS = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<RangeKey, Integer> preComputedHighs = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<RangeKey, Integer> preComputedLows = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<MovingAvgKey, Double> preComputedMoving = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<VolatilityKey, Double> preComputedVolatility = new ConcurrentHashMap<>(100000);
    public static final ConcurrentHashMap<AvgVolumeKey, Double> preComputedAvgVolume = new ConcurrentHashMap<>(100000);
    public static final Lock lock = new ReentrantLock();

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
            if (i < period) {
                movingAverages.add(null);
            } else {
                movingAverages.add(calculateSlidingAvg(prices, i, period, stock.stock().ticker_symbol()));
            }
        }
        return movingAverages;
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
