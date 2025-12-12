package com.stock.analyzer.util;

import com.stock.analyzer.model.dto.StockGraphState;
import com.stock.analyzer.model.dto.StockTrade;
import com.stock.analyzer.core.Simulation;
import com.stock.analyzer.core.StocksTradeTimeFrame;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class StatsCalculator {
    public static final ConcurrentHashMap<String, Simulation> SIMULATIONS = new ConcurrentHashMap<>(10000000);
    public static final ConcurrentHashMap<String, Integer> preComputedHighs = new ConcurrentHashMap<>(10000000);
    public static final ConcurrentHashMap<String, Integer> preComputedLows = new ConcurrentHashMap<>(10000000);
    public static final ConcurrentHashMap<String, Double> preComputedRSI = new ConcurrentHashMap<>(10000000);
    public static final ConcurrentHashMap<String, Double> preComputedMoving = new ConcurrentHashMap<>(10000000);
    public static final Lock lock = new ReentrantLock();

    public static Simulation getOrAddSimulation(com.stock.analyzer.core.SimulationConfig config) {
        return SIMULATIONS.computeIfAbsent(config.generateKey(), k -> new Simulation(config));
    }

    public static void AddSimulation(Simulation simulation) {
        SIMULATIONS.put(simulation.key, simulation);
    }

    public static double calculateSlidingAvg(List<Double> list, int endIndex, int timeFrame, String key) {
        return preComputedMoving.computeIfAbsent(key, k -> {
            double sum = 0.0;
            for (int i = endIndex - timeFrame + 1; i <= endIndex; i++) {
                sum += list.get(i);
            }
            return sum / timeFrame;
        });
    }

    public static int[] findLowestAndHighest(List<Double> prices, int start, int end) {
        int lowest = start, highest = start;
        for (int i = start + 1; i < end; i++) {
            if (prices.get(i) < prices.get(lowest))
                lowest = i;
            if (prices.get(i) > prices.get(highest))
                highest = i;
        }
        return new int[] { lowest, highest };
    }

    public static List<Double> MovingAvg(StockGraphState stock, int period) {
        List<Double> movingAverages = new ArrayList<>();

        for (int i = 0; i < stock.closePrices().size(); i++) {
            if (i < period) {
                movingAverages.add(null);
            } else {
                movingAverages.add(
                        stock.closePrices().subList(i - period, i).stream().mapToDouble(price -> price).sum() / period);
            }
        }

        return movingAverages;
    }

    public static void WriteStat() {
        String filePath = "D:\\data.csv";

        // Data to write to the CSV

        try (FileWriter writer = new FileWriter(filePath)) {
            // Write the data
            var sortedSimulation = new ArrayList<>(SIMULATIONS.values()).stream()
                    .sorted(Comparator.comparingDouble(Simulation::calculateSimulationScore)).toList();
            for (Simulation simulation : sortedSimulation) {
                writer.append(simulation.toString());
                writer.append("\n\n\n\n");
            }

            System.out.println("CSV file created successfully at: " + filePath);
        } catch (IOException e) {
            System.err.println("An error occurred while writing the CSV file.");
            e.printStackTrace();
        }
    }

    public static void WriteStatEvals() {
        String filePath = "D:\\evals.csv";

        // Data to write to the CSV

        try (FileWriter writer = new FileWriter(filePath)) {
            // Write the data
            var sortedSimulation = SIMULATIONS.values().stream().sorted(Comparator.comparingDouble(Simulation::getEval))
                    .toList();
            for (Simulation simulation : sortedSimulation) {
                for (StocksTradeTimeFrame tradeTimeFrame : simulation.timeFrames.values()) {
                    for (StockTrade stockTrade : tradeTimeFrame.Trades()) {
                        writer.append(stockTrade.getTicker() + " " + stockTrade.eval);
                        writer.append("\n");
                    }
                }
            }

            System.out.println("CSV file created successfully at: " + filePath);
        } catch (IOException e) {
            System.err.println("An error occurred while writing the CSV file.");
            e.printStackTrace();
        }
    }

    public static double calculateRSI(List<Double> closingPrices, String key) {
        if (preComputedRSI.containsKey(key)) {
            return preComputedRSI.get(key);
        }

        var period = 14;
        if (closingPrices.size() < period + 1) {
            throw new IllegalArgumentException("Not enough data to calculate RSI for the given period.");
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        for (int i = 1; i <= period; i++) {
            double change = closingPrices.get(i) - closingPrices.get(i - 1);
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(-change);
            }
        }

        double averageGain = gains.stream().mapToDouble(Double::doubleValue).sum() / period;
        double averageLoss = losses.stream().mapToDouble(Double::doubleValue).sum() / period;

        for (int i = period + 1; i < closingPrices.size(); i++) {
            double change = closingPrices.get(i) - closingPrices.get(i - 1);
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? -change : 0;

            averageGain = ((averageGain * (period - 1)) + gain) / period;
            averageLoss = ((averageLoss * (period - 1)) + loss) / period;
        }

        if (averageLoss == 0) {
            return 100.0; // RSI is 100 when there are no losses
        }

        double rs = averageGain / averageLoss;
        var result = 100 - (100 / (1 + rs));
        preComputedRSI.put(key, result);
        return result;
    }
}
